package com.ishita.tripcoordinatorplatform.intelligence.google.accounting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Explicitly activated JDBC bookkeeping only: no entity scanning, DDL, seeds or startup writes. */
@Component
@ConditionalOnProperty(name="app.explore.google.accounting.mode",havingValue="durable")
public class GoogleUsageLedger {
    public record Slot(String key, GoogleSku sku) {}
    public record Attempt(UUID id, String key, GoogleSku sku) {}
    public record Phase(UUID id, List<Attempt> attempts) {}
    public record Authorization(UUID operationId, UUID fence) {}
    public record Completion(Authorization authorization, boolean succeeded) {}
    /** Internal administrative command, never exposed through an HTTP API or invoked at startup. */
    public enum AccountingMode { TRACK_ONLY, OPTIONAL_HARD_CAP }
    public record ApprovedPolicy(UUID revisionId,UUID expectedParent,long hourlyMicros,long dailyMicros,long monthlyMicros,
                                 boolean enabled,Instant effectiveAt,Instant validUntil,String approvedBy,String approvalReference,
                                 String reason,Map<Long,Long> holdTargets,AccountingMode mode) {
        public ApprovedPolicy(UUID revisionId,UUID expectedParent,long hourlyMicros,long dailyMicros,long monthlyMicros,boolean enabled,Instant effectiveAt,Instant validUntil,String approvedBy,String approvalReference,String reason,Map<Long,Long> holdTargets) {
            this(revisionId,expectedParent,hourlyMicros,dailyMicros,monthlyMicros,enabled,effectiveAt,validUntil,approvedBy,approvalReference,reason,holdTargets,AccountingMode.OPTIONAL_HARD_CAP);
        }
    }
    public record Timing(long transactions, long totalNanos, List<Long> samplesNanos) {}
    private record Policy(UUID id, String book, String digest, int protocol, boolean enabled, Instant effective,
                          Instant validUntil, long hour, long day, long month, AccountingMode mode) {}
    private record Window(long id, String key, Instant end, UUID revision, long limit, long reserved, long charged, long hold) {}
    private record Reservation(UUID id, UUID revision, long hour, long day, long month, String state, Instant expires, String digest) {
        List<Long> windows() { return List.of(hour,day,month); }
    }
    private record Operation(UUID id, String key, String book, String kind, String sku, int units, long unitPrice, long cost, String state, UUID fence) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String scope;
    private java.sql.Connection topologyLease;
    private Integer topologyPid;
    @Value("${app.explore.google.accounting.rpc-enabled:false}") private boolean rpcEnabled;
    public static final String RPC_BODY_MD5="ab4d5dcabe0c34b8980d778d5f24a3d6";
    private final tools.jackson.databind.json.JsonMapper json=new tools.jackson.databind.json.JsonMapper();
    public void rpcForVerification(boolean enabled) {rpcEnabled=enabled;}
    private final AtomicLong lastRecovery = new AtomicLong();
    private final Map<String,Stats> metrics = new ConcurrentHashMap<>();
    private static final class Stats {
        long transactions, nanos;
        final ArrayDeque<Long> samples=new ArrayDeque<>();
        synchronized void add(long value,boolean transaction) { if(transaction) transactions++; nanos+=value; if(samples.size()==512) samples.removeFirst(); samples.addLast(value); }
        synchronized Timing snapshot() { return new Timing(transactions,nanos,List.copyOf(samples)); }
    }

    public GoogleUsageLedger(DataSource source, PlatformTransactionManager manager,
            @Value("${app.explore.google.accounting.scope:}") String scope) {
        if(scope==null || !scope.matches("[A-Za-z0-9_.:-]{1,96}")) throw new IllegalArgumentException("Durable Google accounting requires an explicit stable billing scope");
        this.scope=scope; this.jdbc=new JdbcTemplate(source);
        this.transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
    }
    /** A durable deployment supports exactly one capacity coordinator per billing scope. Advisory lease, no persisted startup writes. */
    @jakarta.annotation.PostConstruct public void verifyReady() {
        try {
            topologyLease=jdbc.getDataSource().getConnection();
            try(var statement=topologyLease.prepareStatement("SELECT pg_backend_pid(),pg_try_advisory_lock(hashtextextended(?,734621))")) {
                statement.setString(1,"explore-capacity:"+scope);
                try(var rows=statement.executeQuery()) {if(!rows.next() || !rows.getBoolean(2)) throw error("TOPOLOGY_CONFLICT","Another durable capacity coordinator owns this billing scope; multi-replica dispatch is unsupported.");topologyPid=rows.getInt(1);}
            }
            var ids=jdbc.query("SELECT active_revision_id FROM explore_provider_usage_policies WHERE budget_scope=?",(rs,n)->rs.getObject(1,UUID.class),scope);
            if(ids.size()!=1||ids.getFirst()==null) throw error("POLICY_MISSING","Approved policy is required before durable startup.");
            compatible(policy(ids.getFirst()),Instant.now());
            if(rpcEnabled && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*)=1 FROM pg_proc WHERE oid=to_regprocedure('public.explore_google_phase_rpc(text,text,jsonb)') AND md5(prosrc)=? AND NOT prosecdef AND has_function_privilege(current_user,oid,'EXECUTE')",Boolean.class,RPC_BODY_MD5))) throw error("ACCOUNTING_UNAVAILABLE","Approved phase RPC installation/privileges are required.");
        } catch(RuntimeException | java.sql.SQLException failure) {
            releaseTopology();
            if(failure instanceof GoogleAccountingException accounting) throw accounting;
            throw error("ACCOUNTING_UNAVAILABLE","Durable accounting readiness failed; provider dispatch remains unavailable.");
        }
    }
    @jakarta.annotation.PreDestroy public void releaseTopology() {
        if(topologyLease!=null) try {
            try(var statement=topologyLease.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,734621))")) {statement.setString(1,"explore-capacity:"+scope);statement.execute();}
        } catch(java.sql.SQLException ignored) {} finally {try {topologyLease.close();} catch(java.sql.SQLException ignored) {} topologyLease=null;topologyPid=null;}
    }
    private void checkTopology() {
        if(topologyLease!=null) try {if(topologyLease.isClosed()) throw error("TOPOLOGY_LOST","Capacity coordinator connection lost; provider dispatch is stopped.");}
        catch(java.sql.SQLException failure) {throw error("TOPOLOGY_LOST","Capacity coordinator connection lost; provider dispatch is stopped.");}
    }
    private Map<String,Object> rpcInput() {
        var result=new LinkedHashMap<String,Object>();result.put("book",GoogleRateBook.VERSION);result.put("digest",GoogleRateBook.DIGEST);result.put("leasePid",topologyPid);
        var prices=new TreeMap<String,Object>();for(var sku:GoogleSku.values())prices.put(sku.skuId,Map.of("kind",sku.operationKind,"price",sku.unitMicros));result.put("prices",prices);return result;
    }
    /** One autocommit SQL statement = one acknowledged atomic database transaction; never a network call under a DB lock. */
    private tools.jackson.databind.JsonNode rpc(String stage,String command,Map<String,Object> input) {
        long start=System.nanoTime();
        try(var connection=rpcConnection(stage)) {
            if(!connection.getAutoCommit()) throw error("ACCOUNTING_UNAVAILABLE","Phase RPC requires an independent autocommit connection.");
            try(var statement=connection.prepareStatement("SELECT public.explore_google_phase_rpc(?,?,?::jsonb) FROM pg_proc WHERE oid=to_regprocedure('public.explore_google_phase_rpc(text,text,jsonb)') AND md5(prosrc)=? AND NOT prosecdef")) {
                statement.setQueryTimeout(5);statement.setString(1,scope);statement.setString(2,command);statement.setString(3,json.writeValueAsString(input));statement.setString(4,RPC_BODY_MD5);
                try(var rows=statement.executeQuery()) {if(!rows.next()) throw error("ACCOUNTING_UNAVAILABLE","Accounting commit was not acknowledged.");return json.readTree(rows.getString(1));}
            }
        } catch(GoogleAccountingException failure) {throw failure;}
        catch(java.sql.SQLException failure) {
            // Only our allowlisted protocol marker escapes; never SQL, driver output, or parameters.
            String[] codes={"COST_BUDGET_EXHAUSTED","POLICY_MISSING","POLICY_CHANGED","POLICY_PAUSED","PRICE_BOOK_EXPIRED","TOPOLOGY_LOST","LEDGER_INVALID","UNSUPPORTED_SKU","IDEMPOTENCY_CONFLICT","PHASE_MISSING","PHASE_EXPIRED","DUPLICATE_DISPATCH","DUPLICATE_COMPLETION","WRONG_FENCE"};
            for(String code:codes) if("P0001".equals(failure.getSQLState()) && failure.getMessage().contains("ERROR: "+code)) {
                if(code.equals("COST_BUDGET_EXHAUSTED")) {
                    var match=java.util.regex.Pattern.compile("COST_BUDGET_EXHAUSTED\\|([0-9TZ:-]+)\\|([A-Z,]+)").matcher(failure.getMessage());boolean matched=match.find();Instant retry=matched ? Instant.parse(match.group(1)):null;String windows=matched ? match.group(2):"unknown calendar policy";
                    throw new GoogleAccountingException(code,"Approved Google emergency exposure exhausted ("+windows+"). Next blocking calendar boundary: "+retry+". No new dispatch permitted; other usage also affects eligibility.",retry);
                }
                throw error(code,"Google accounting stopped safely ("+code+"); no unacknowledged provider dispatch is permitted.");
            }
            throw error("ACCOUNTING_UNAVAILABLE","Atomic Google accounting unavailable or acknowledgment uncertain; no new dispatch permitted.");
        } catch(RuntimeException failure) {throw error("ACCOUNTING_UNAVAILABLE","Atomic Google accounting response invalid; no new dispatch permitted.");}
        finally {metrics.computeIfAbsent(stage,k->new Stats()).add(System.nanoTime()-start,true);}
    }
    private java.sql.Connection rpcConnection(String stage) throws java.sql.SQLException {
        long started=System.nanoTime();
        try { return jdbc.getDataSource().getConnection(); }
        finally { metrics.computeIfAbsent(stage+"ConnectionWait",k->new Stats()).add(System.nanoTime()-started,false); }
    }
    private Operation operation(tools.jackson.databind.JsonNode o) {
        return new Operation(UUID.fromString(o.path("id").asText()),o.path("operation_key").asText(),o.path("price_book_version").asText(),o.path("operation_kind").asText(),o.path("sku").asText(),o.path("billable_units").asInt(),o.path("unit_price_micros").asLong(),o.path("estimated_cost_micros").asLong(),o.path("state").asText(),o.path("dispatch_fence").isNull() ? null:UUID.fromString(o.path("dispatch_fence").asText()));
    }
    public record UsageSnapshot(String mode,boolean admissionsEnabled,Map<String,Long> chargedMicros,Map<String,Long> reservedMicros,Map<String,Long> windowIds) {}
    /** Read-only reporting, never a source of dispatch authorization or actual Cloud billing. */
    public UsageSnapshot usageSnapshot() {
        var charged=new TreeMap<String,Long>();var reserved=new TreeMap<String,Long>();var windowIds=new TreeMap<String,Long>();
        var rows=jdbc.queryForList("SELECT r.accounting_mode,r.admissions_enabled,w.id,w.policy_key,w.charged_micros,w.reserved_micros FROM explore_provider_usage_policies p JOIN explore_provider_usage_policy_revisions r ON r.id=p.active_revision_id AND r.budget_scope=p.budget_scope LEFT JOIN explore_provider_usage_windows w ON w.budget_scope=p.budget_scope AND w.starts_at<=clock_timestamp() AND w.ends_at>clock_timestamp() WHERE p.budget_scope=?",scope);
        if(rows.isEmpty())throw error("POLICY_MISSING","Accounting reporting requires an active policy");
        for(var row:rows)if(row.get("policy_key")!=null) {String key=row.get("policy_key").toString();windowIds.put(key,((Number)row.get("id")).longValue());charged.put(key,((Number)row.get("charged_micros")).longValue());reserved.put(key,((Number)row.get("reserved_micros")).longValue());}
        return new UsageSnapshot(rows.getFirst().get("accounting_mode").toString(),(boolean)rows.getFirst().get("admissions_enabled"),Map.copyOf(charged),Map.copyOf(reserved),Map.copyOf(windowIds));
    }
    public Map<String,Timing> timings() {
        var result=new TreeMap<String,Timing>(); metrics.forEach((k,v)->result.put(k,v.snapshot())); return Map.copyOf(result);
    }
    private <T> T tx(String stage, Supplier<T> work) {
        long start=System.nanoTime();
        try {
            return transactions.execute(status -> {
                if(!stage.equals("authorization") && !stage.equals("settlement")) jdbc.execute("SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='4s'");
                return work.get();
            });
        } catch (GoogleAccountingException failure) { throw failure; }
        catch (RuntimeException failure) {
            // No raw SQL, JDBC URL, credentials or driver text escape this boundary.
            throw new GoogleAccountingException("ACCOUNTING_UNAVAILABLE","Durable Google accounting is unavailable; provider work cannot continue safely.");
        } finally { metrics.computeIfAbsent(stage,k->new Stats()).add(System.nanoTime()-start,true); }
    }
    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    private UUID lockScope() {
        var ids=jdbc.query("SELECT active_revision_id FROM explore_provider_usage_policies WHERE budget_scope=? FOR UPDATE",
                (rs,n)->rs.getObject(1,UUID.class),scope);
        if(ids.size()!=1 || ids.getFirst()==null) throw error("POLICY_MISSING","No approved Google cost policy is active.");
        return ids.getFirst();
    }
    private record Admission(Policy policy,Instant time) {}
    private Admission lockActivePolicy() {
        var rows=jdbc.query("SELECT r.*,clock_timestamp() AS observed_at FROM explore_provider_usage_policies p JOIN explore_provider_usage_policy_revisions r ON r.id=p.active_revision_id AND r.budget_scope=p.budget_scope WHERE p.budget_scope=? AND (?::int IS NULL OR EXISTS(SELECT 1 FROM pg_locks l WHERE l.pid=? AND l.locktype='advisory' AND l.granted AND l.classid::bigint=((hashtextextended(?,734621)>>32)&4294967295) AND l.objid::bigint=(hashtextextended(?,734621)&4294967295) AND l.objsubid=1)) FOR UPDATE OF p",(rs,n)->new Admission(new Policy(
            rs.getObject("id",UUID.class),rs.getString("price_book_version"),rs.getString("price_book_digest"),rs.getInt("protocol_version"),rs.getBoolean("admissions_enabled"),rs.getTimestamp("effective_at").toInstant(),rs.getTimestamp("admission_valid_until").toInstant(),rs.getLong("hourly_limit_micros"),rs.getLong("daily_limit_micros"),rs.getLong("monthly_limit_micros"),AccountingMode.valueOf(rs.getString("accounting_mode"))),rs.getTimestamp("observed_at").toInstant()),scope,topologyPid,topologyPid,"explore-capacity:"+scope,"explore-capacity:"+scope);
        if(rows.size()!=1) throw error("POLICY_MISSING","No approved Google cost policy is active.");return rows.getFirst();
    }
    private Policy policy(UUID id) {
        return jdbc.queryForObject("SELECT * FROM explore_provider_usage_policy_revisions WHERE id=? AND budget_scope=?",(rs,n)->new Policy(
                id,rs.getString("price_book_version"),rs.getString("price_book_digest"),rs.getInt("protocol_version"),rs.getBoolean("admissions_enabled"),
                rs.getTimestamp("effective_at").toInstant(),rs.getTimestamp("admission_valid_until").toInstant(),rs.getLong("hourly_limit_micros"),rs.getLong("daily_limit_micros"),rs.getLong("monthly_limit_micros"),AccountingMode.valueOf(rs.getString("accounting_mode"))),id,scope);
    }
    private void compatible(Policy p, Instant time) {
        if(p.protocol!=1 || !GoogleRateBook.VERSION.equals(p.book) || !GoogleRateBook.DIGEST.equals(p.digest))
            throw error("POLICY_CHANGED","Google accounting policy/rate book is incompatible with this application; no new provider dispatch is permitted.");
        if(!p.enabled) throw error("POLICY_PAUSED","Google paid-provider admission is paused by the approved policy.");
        if(time.isBefore(p.effective)||!time.isBefore(p.validUntil)) throw error("PRICE_BOOK_EXPIRED","Google pricing approval is not currently valid; an approved policy revision is required.");
    }
    private List<Window> lockWindows(List<Long> ids) {
        if(ids.size()!=3 || new HashSet<>(ids).size()!=3) throw error("LEDGER_INVALID","Google accounting phase has invalid calendar windows.");
        var windows=jdbc.query("SELECT * FROM explore_provider_usage_windows WHERE id IN (?,?,?) AND budget_scope=? ORDER BY id FOR UPDATE",(rs,n)->new Window(
                rs.getLong("id"),rs.getString("policy_key"),rs.getTimestamp("ends_at").toInstant(),rs.getObject("applied_policy_revision_id",UUID.class),
                rs.getLong("limit_micros"),rs.getLong("reserved_micros"),rs.getLong("charged_micros"),rs.getLong("uncertainty_hold_micros")),ids.get(0),ids.get(1),ids.get(2),scope);
        if(windows.size()!=3) throw error("LEDGER_INVALID","Google accounting calendar windows are missing.");
        if(!windows.stream().map(Window::key).collect(java.util.stream.Collectors.toSet()).equals(Set.of("GOOGLE_UTC_HOUR","GOOGLE_UTC_DAY","GOOGLE_UTC_MONTH")))
            throw error("LEDGER_INVALID","Google accounting calendar window types are inconsistent.");
        return windows;
    }
    private Reservation reservation(UUID id, boolean lock) {
        var values=jdbc.query("SELECT * FROM explore_provider_usage_reservations WHERE id=?"+(lock ? " FOR UPDATE":""),(rs,n)->new Reservation(
                rs.getObject("id",UUID.class),rs.getObject("policy_revision_id",UUID.class),rs.getLong("hourly_window_id"),rs.getLong("daily_window_id"),rs.getLong("monthly_window_id"),
                rs.getString("state"),rs.getTimestamp("expires_at").toInstant(),rs.getString("operation_plan_digest")),id);
        if(values.size()!=1) throw error("PHASE_MISSING","Google accounting phase is unavailable.");
        return values.getFirst();
    }
    private List<Operation> lockOperations(UUID phase) {
        return jdbc.query("SELECT * FROM explore_provider_usage_operations WHERE reservation_id=? ORDER BY id FOR UPDATE",(rs,n)->new Operation(
                rs.getObject("id",UUID.class),rs.getString("operation_key"),rs.getString("price_book_version"),rs.getString("operation_kind"),rs.getString("sku"),rs.getInt("billable_units"),rs.getLong("unit_price_micros"),rs.getLong("estimated_cost_micros"),rs.getString("state"),rs.getObject("dispatch_fence",UUID.class)),phase);
    }
    private record WindowFrame(List<Window> windows,Instant time) {}
    private WindowFrame admissionWindows(Policy p,List<ZonedDateTime> starts,List<ZonedDateTime> ends,List<String> keys,List<Long> limits) {
        var values=new ArrayList<String>();var predicates=new ArrayList<String>();
        for(int i=0;i<3;i++) {
            String start=starts.get(i).toInstant().toString(),end=ends.get(i).toInstant().toString();
            values.add("('"+keys.get(i)+"','"+scope+"','"+start+"','"+end+"','"+p.id+"',"+limits.get(i)+")");
            predicates.add("(policy_key='"+keys.get(i)+"' AND starts_at='"+start+"')");
        }
        String sql="INSERT INTO explore_provider_usage_windows(policy_key,budget_scope,starts_at,ends_at,applied_policy_revision_id,limit_micros) VALUES "+String.join(",",values)+" ON CONFLICT(policy_key,budget_scope,starts_at) DO NOTHING;"
            +"SELECT * FROM explore_provider_usage_windows WHERE budget_scope='"+scope+"' AND ("+String.join(" OR ",predicates)+") ORDER BY id FOR UPDATE;SELECT clock_timestamp();";
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<WindowFrame>) connection->{
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(4);statement.execute(sql);statement.getMoreResults();var windows=new ArrayList<Window>();
                try(var rs=statement.getResultSet()) {while(rs.next()) windows.add(new Window(rs.getLong("id"),rs.getString("policy_key"),rs.getTimestamp("ends_at").toInstant(),rs.getObject("applied_policy_revision_id",UUID.class),rs.getLong("limit_micros"),rs.getLong("reserved_micros"),rs.getLong("charged_micros"),rs.getLong("uncertainty_hold_micros")));}
                statement.getMoreResults();try(var rs=statement.getResultSet()) {rs.next();return new WindowFrame(List.copyOf(windows),rs.getTimestamp(1).toInstant());}
            }
        });
    }
    public Phase reserve(UUID requestId, String phaseKey, List<Slot> slots) {
        if(requestId==null || phaseKey==null || !phaseKey.matches("[A-Za-z0-9_-]{1,96}") || slots==null || slots.isEmpty() || slots.size()>128)
            throw new IllegalArgumentException("Invalid server accounting phase plan");
        if(slots.stream().anyMatch(s->s.sku()==null||s.key()==null||!s.key().matches("[A-Za-z0-9_-]{1,96}")) || slots.stream().map(Slot::key).distinct().count()!=slots.size())
            throw new IllegalArgumentException("Invalid server accounting operation slots");
        var snapshot=List.copyOf(slots);
        var digest=GoogleRateBook.digest(GoogleRateBook.DIGEST+snapshot.stream().map(s->s.key()+":"+s.sku().name()+":1").reduce("",(a,b)->a+"\n"+b));
        long amount=snapshot.stream().mapToLong(s->s.sku().unitMicros).reduce(0,Math::addExact);
        recoverPeriodically();
        if(rpcEnabled) {
            var input=rpcInput();input.put("requestId",requestId);input.put("phaseKey",phaseKey);input.put("planDigest",digest);
            input.put("slots",snapshot.stream().map(slot->Map.of("key",slot.key,"sku",slot.sku.skuId,"kind",slot.sku.operationKind,"price",slot.sku.unitMicros)).toList());
            var reply=rpc("reservation","RESERVE",input);List<Operation> operations=new ArrayList<>();reply.path("operations").forEach(o->operations.add(operation(o)));
            return phase(UUID.fromString(reply.path("id").asText()),operations,snapshot);
        }
        return tx("reservation",()->{
            var admission=lockActivePolicy();var p=admission.policy;Instant admissionTime=admission.time;compatible(p,admissionTime);
            var existing=jdbc.query("SELECT id FROM explore_provider_usage_reservations WHERE request_id=? AND phase_key=?",(rs,n)->rs.getObject(1,UUID.class),requestId,phaseKey);
            if(!existing.isEmpty()) {
                var read=reservation(existing.getFirst(),false); lockWindows(read.windows()); var r=reservation(read.id,true);
                var operations=lockOperations(r.id);
                if(!r.digest.equals(digest)) throw error("IDEMPOTENCY_CONFLICT","Repeated Google phase identity has a different server plan.");
                return phase(r.id,operations,snapshot);
            }
            Instant time=admissionTime; var utc=time.atZone(ZoneOffset.UTC);
            var starts=List.of(utc.truncatedTo(java.time.temporal.ChronoUnit.HOURS),utc.toLocalDate().atStartOfDay(ZoneOffset.UTC),utc.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC));
            var ends=List.of(starts.get(0).plusHours(1),starts.get(1).plusDays(1),starts.get(2).plusMonths(1));
            var keys=List.of("GOOGLE_UTC_HOUR","GOOGLE_UTC_DAY","GOOGLE_UTC_MONTH"); var limits=List.of(p.hour,p.day,p.month);
            var frame=admissionWindows(p,starts,ends,keys,limits);var windows=frame.windows;
            if(windows.size()!=3) throw error("LEDGER_INVALID","Accounting window creation/locking incomplete.");
            Instant lockedTime=frame.time;compatible(p,lockedTime);checkWindows(windows,p,lockedTime,amount,true);
            UUID id=UUID.randomUUID();
            long hour=windowId(windows,keys.get(0)),day=windowId(windows,keys.get(1)),month=windowId(windows,keys.get(2));
            Instant expires=Collections.min(List.of(lockedTime.plusSeconds(45),ends.get(0).toInstant(),p.validUntil));
            List<Attempt> attempts=new ArrayList<>();List<Object> insert=new ArrayList<>();
            Collections.addAll(insert,id,requestId,phaseKey,p.id,digest,hour,day,month,Timestamp.from(expires));
            for(var slot:snapshot) {UUID op=UUID.randomUUID();attempts.add(new Attempt(op,slot.key,slot.sku));Collections.addAll(insert,op,slot.key,slot.sku.operationKind,slot.sku.skuId,GoogleRateBook.VERSION,slot.sku.unitMicros);}
            Collections.addAll(insert,amount,hour,day,month,snapshot.size());
            String values=String.join(",",Collections.nCopies(snapshot.size(),"(?::uuid,?::text,?::text,?::text,?::text,?::bigint)"));
            int changed=jdbc.update("WITH phase AS (INSERT INTO explore_provider_usage_reservations(id,request_id,phase_key,policy_revision_id,operation_plan_digest,hourly_window_id,daily_window_id,monthly_window_id,expires_at) VALUES(?,?,?,?,?,?,?,?,?) RETURNING id), slots AS (INSERT INTO explore_provider_usage_operations(id,reservation_id,operation_key,operation_kind,sku,price_book_version,billable_units,unit_price_micros) SELECT v.id,p.id,v.key,v.kind,v.sku,v.book,1,v.price FROM (VALUES "+values+") v(id,key,kind,sku,book,price) CROSS JOIN phase p RETURNING id) UPDATE explore_provider_usage_windows SET reserved_micros=reserved_micros+? WHERE id IN(?,?,?) AND (SELECT count(*) FROM slots)=?",insert.toArray());
            if(changed!=3) throw error("LEDGER_INVALID","Reservation/counter batch incomplete; transaction rolled back.");
            return new Phase(id,List.copyOf(attempts));
        });
    }
    private Phase phase(UUID id, List<Operation> operations, List<Slot> slots) {
        if(operations.size()!=slots.size()) throw error("LEDGER_INVALID","Google accounting operation plan is incomplete.");
        Map<String,Operation> byKey=new HashMap<>(); operations.forEach(o->byKey.put(o.key,o));
        var attempts=slots.stream().map(s->{var o=byKey.get(s.key); if(o==null||o.cost!=s.sku.unitMicros||o.unitPrice!=s.sku.unitMicros||o.units!=1||!o.sku.equals(s.sku.skuId)||!o.kind.equals(s.sku.operationKind)||!o.book.equals(GoogleRateBook.VERSION)) throw error("IDEMPOTENCY_CONFLICT","Google accounting operation snapshot mismatch."); return new Attempt(o.id,o.key,s.sku);}).toList();
        return new Phase(id,attempts);
    }
    private long windowId(List<Window> windows,String key) { return windows.stream().filter(w->w.key.equals(key)).findFirst().orElseThrow().id; }
    private void checkWindows(List<Window> windows,Policy p,Instant time,long additional,boolean reserve) {
        List<Window> blocked=new ArrayList<>();
        for(var w:windows) {
            long approved=switch(w.key) {case "GOOGLE_UTC_HOUR" -> p.hour;case "GOOGLE_UTC_DAY" -> p.day;case "GOOGLE_UTC_MONTH" -> p.month;default -> throw error("LEDGER_INVALID","Unexpected accounting window type");};
            if(w.limit!=approved) throw error("LEDGER_INVALID","Google window cap does not match its approved policy revision.");
            if(!w.revision.equals(p.id)||!time.isBefore(w.end)) throw error("POLICY_CHANGED","Google accounting window changed or expired; retry with a new phase.");
            // Overflow fails closed. Do not clamp persisted counters or rewrite historical charges.
            long total=Math.addExact(Math.addExact(w.charged,w.reserved),w.hold);
            if(p.mode==AccountingMode.OPTIONAL_HARD_CAP && (total>w.limit || (reserve && additional>w.limit-total))) blocked.add(w);
        }
        if(!blocked.isEmpty()) {
            Instant boundary=blocked.stream().map(Window::end).max(Comparator.naturalOrder()).orElseThrow();
            String names=String.join(", ",blocked.stream().map(w->w.key.replace("GOOGLE_UTC_","")).toList());
            String display=java.time.format.DateTimeFormatter.ofPattern("MMM d, uuuu HH:mm 'UTC'",Locale.ENGLISH).withZone(ZoneOffset.UTC).format(boundary);
            throw new GoogleAccountingException("COST_BUDGET_EXHAUSTED","Google estimated-cost allowance exhausted ("+names+"). Next blocking window boundary: "+display+"; admission also depends on other usage. Results are incomplete.",boundary);
        }
    }
    private record LockedPhase(Policy policy,Reservation reservation,List<Window> windows,List<Operation> operations,Instant time) {}
    /** Pipeline dependent reads in one JDBC execution. PostgreSQL still locks policy → windows → reservation → operations. */
    private LockedPhase lockedPhase(UUID id,boolean dispatch) {
        String lease=!dispatch || topologyPid==null ? "" : " AND EXISTS(SELECT 1 FROM pg_locks l WHERE l.pid="+topologyPid+" AND l.locktype='advisory' AND l.granted AND l.classid::bigint=((hashtextextended('explore-capacity:"+scope+"',734621)>>32)&4294967295) AND l.objid::bigint=(hashtextextended('explore-capacity:"+scope+"',734621)&4294967295) AND l.objsubid=1)";
        // All literals are server-controlled UUIDs or constructor-validated scope identifiers; never provider/client text.
        String sql="SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='4s';SELECT v.* FROM explore_provider_usage_policies p JOIN explore_provider_usage_policy_revisions v ON v.id=p.active_revision_id AND v.budget_scope=p.budget_scope WHERE p.budget_scope='"+scope+"'"+lease+" FOR UPDATE OF p;"
            +"SELECT w.* FROM explore_provider_usage_windows w WHERE w.budget_scope='"+scope+"' AND w.id IN(SELECT hourly_window_id FROM explore_provider_usage_reservations WHERE id='"+id+"' UNION ALL SELECT daily_window_id FROM explore_provider_usage_reservations WHERE id='"+id+"' UNION ALL SELECT monthly_window_id FROM explore_provider_usage_reservations WHERE id='"+id+"') ORDER BY w.id FOR UPDATE;"
            +"SELECT * FROM explore_provider_usage_reservations WHERE id='"+id+"' FOR UPDATE;"
            +"SELECT * FROM explore_provider_usage_operations WHERE reservation_id='"+id+"' ORDER BY id FOR UPDATE;SELECT clock_timestamp();";
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<LockedPhase>) connection->{
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(4);boolean result=statement.execute(sql);
                while(!result) {if(statement.getUpdateCount()==-1) throw error("LEDGER_INVALID","Accounting lock response incomplete.");result=statement.getMoreResults();}
                Policy p;Reservation r;List<Window> windows=new ArrayList<>();List<Operation> operations=new ArrayList<>();Instant time;
                try(var rs=statement.getResultSet()) {
                    if(!rs.next()) throw error("POLICY_MISSING","Approved policy or single-instance capacity lease is unavailable.");
                    p=new Policy(rs.getObject("id",UUID.class),rs.getString("price_book_version"),rs.getString("price_book_digest"),rs.getInt("protocol_version"),rs.getBoolean("admissions_enabled"),rs.getTimestamp("effective_at").toInstant(),rs.getTimestamp("admission_valid_until").toInstant(),rs.getLong("hourly_limit_micros"),rs.getLong("daily_limit_micros"),rs.getLong("monthly_limit_micros"),AccountingMode.valueOf(rs.getString("accounting_mode")));
                }
                statement.getMoreResults();try(var rs=statement.getResultSet()) {while(rs.next()) windows.add(new Window(rs.getLong("id"),rs.getString("policy_key"),rs.getTimestamp("ends_at").toInstant(),rs.getObject("applied_policy_revision_id",UUID.class),rs.getLong("limit_micros"),rs.getLong("reserved_micros"),rs.getLong("charged_micros"),rs.getLong("uncertainty_hold_micros")));}
                if(windows.size()!=3 || !windows.stream().map(Window::key).collect(java.util.stream.Collectors.toSet()).equals(Set.of("GOOGLE_UTC_HOUR","GOOGLE_UTC_DAY","GOOGLE_UTC_MONTH"))) throw error("LEDGER_INVALID","Accounting phase window scope/type mismatch.");
                statement.getMoreResults();try(var rs=statement.getResultSet()) {
                    if(!rs.next()) throw error("PHASE_MISSING","Google accounting phase is unavailable.");
                    r=new Reservation(rs.getObject("id",UUID.class),rs.getObject("policy_revision_id",UUID.class),rs.getLong("hourly_window_id"),rs.getLong("daily_window_id"),rs.getLong("monthly_window_id"),rs.getString("state"),rs.getTimestamp("expires_at").toInstant(),rs.getString("operation_plan_digest"));
                }
                statement.getMoreResults();try(var rs=statement.getResultSet()) {while(rs.next()) operations.add(new Operation(rs.getObject("id",UUID.class),rs.getString("operation_key"),rs.getString("price_book_version"),rs.getString("operation_kind"),rs.getString("sku"),rs.getInt("billable_units"),rs.getLong("unit_price_micros"),rs.getLong("estimated_cost_micros"),rs.getString("state"),rs.getObject("dispatch_fence",UUID.class)));}
                statement.getMoreResults();try(var rs=statement.getResultSet()) {rs.next();time=rs.getTimestamp(1).toInstant();}
                return new LockedPhase(p,r,List.copyOf(windows),List.copyOf(operations),time);
            }
        });
    }
    public List<Authorization> authorize(UUID phaseId,List<UUID> requested) {
        checkTopology();
        if(requested.isEmpty()||new HashSet<>(requested).size()!=requested.size()) throw new IllegalArgumentException("Invalid authorization batch");
        if(rpcEnabled) {
            var input=rpcInput();input.put("phaseId",phaseId);input.put("operationIds",requested);
            var reply=rpc("authorization","AUTHORIZE",input);List<Authorization> results=new ArrayList<>();
            reply.forEach(o->results.add(new Authorization(UUID.fromString(o.path("operationId").asText()),UUID.fromString(o.path("fence").asText()))));
            if(results.size()!=requested.size() || !results.stream().map(Authorization::operationId).collect(java.util.stream.Collectors.toSet()).equals(new HashSet<>(requested)) || results.stream().map(Authorization::fence).distinct().count()!=results.size()) throw error("ACCOUNTING_UNAVAILABLE","Authorization response incomplete; no dispatch permitted.");return List.copyOf(results);
        }
        return tx("authorization",()->{
            var locked=lockedPhase(phaseId,true);var p=locked.policy;var windows=locked.windows;var r=locked.reservation;var operations=locked.operations;Instant time=locked.time;
            compatible(p,time); checkWindows(windows,p,time,0,false);
            var original=r.revision.equals(p.id) ? p:policy(r.revision);
            if(!original.book.equals(p.book)||!original.digest.equals(p.digest)||original.protocol!=p.protocol) throw error("POLICY_CHANGED","Google phase was reserved under incompatible pricing; replan unsent work.");
            if(!r.state.equals("OPEN")||!time.isBefore(r.expires)) throw error("PHASE_EXPIRED","Google phase is closed or expired; no dispatch was authorized.");
            Map<UUID,Operation> byId=new HashMap<>(); operations.forEach(o->byId.put(o.id,o));
            long amount=0; List<Authorization> results=new ArrayList<>(); List<Object[]> updates=new ArrayList<>();
            for(UUID id:requested) {
                var o=byId.get(id);
                if(o==null||!o.state.equals("RESERVED")||!o.book.equals(p.book)) throw error("DUPLICATE_DISPATCH","Google operation is unavailable or already authorized; it cannot be dispatched again.");
                if(Arrays.stream(GoogleSku.values()).noneMatch(s->s.operationKind.equals(o.kind)&&s.skuId.equals(o.sku)&&o.units==1&&o.unitPrice==s.unitMicros&&o.cost==s.unitMicros))
                    throw error("UNSUPPORTED_SKU","Google operation snapshot has unsupported pricing; no dispatch was authorized.");
                amount=Math.addExact(amount,o.cost); UUID fence=UUID.randomUUID(); results.add(new Authorization(id,fence)); updates.add(new Object[]{fence,Timestamp.from(time),id});
            }
            String values=String.join(",",Collections.nCopies(updates.size(),"(?::uuid,?::timestamptz,?::uuid)"));
            List<Object> parameters=new ArrayList<>();updates.forEach(row->Collections.addAll(parameters,row));
            Collections.addAll(parameters,amount,amount,r.hour,r.day,r.month,updates.size());
            int changed=jdbc.update("WITH dispatched AS (UPDATE explore_provider_usage_operations o SET state='DISPATCH_AUTHORIZED',dispatch_fence=v.fence,authorized_at=v.at FROM (VALUES "+values+") v(fence,at,id) WHERE o.id=v.id AND o.state='RESERVED' RETURNING o.id) UPDATE explore_provider_usage_windows SET reserved_micros=reserved_micros-?,charged_micros=charged_micros+? WHERE id IN(?,?,?) AND (SELECT count(*) FROM dispatched)=?",parameters.toArray());
            if(changed!=3) throw error("LEDGER_INVALID","Authorization/counter batch was incomplete; transaction rolled back.");
            return List.copyOf(results);
        });
    }
    public void settle(UUID phase,List<Completion> completions) {
        if(rpcEnabled) {
            var input=rpcInput();input.put("phaseId",phase);input.put("completions",completions.stream().map(c->Map.of("operationId",c.authorization.operationId,"fence",c.authorization.fence,"succeeded",c.succeeded)).toList());
            if(!rpc("settlement","SETTLE",input).path("settled").asBoolean()) throw error("ACCOUNTING_UNAVAILABLE","Settlement response incomplete; conservative charges retained.");return;
        }
        tx("settlement",()->{var locked=lockedPhase(phase,false);
            reconcile(locked.reservation,locked.operations,completions,false,locked.time); return null; });
    }
    private void reconcile(Reservation r,List<Operation> operations,List<Completion> completions,boolean recovery,Instant time) {
        Map<UUID,Completion> supplied=new HashMap<>();
        for(var c:completions) if(supplied.put(c.authorization.operationId,c)!=null) throw error("DUPLICATE_COMPLETION","Duplicate Google accounting completion");
        Set<UUID> known=new HashSet<>(); operations.forEach(o->known.add(o.id));
        if(!known.containsAll(supplied.keySet())) throw error("WRONG_FENCE","Google completion does not belong to this phase");
        long release=0; List<Object[]> updates=new ArrayList<>();
        for(var o:operations) {
            var c=supplied.get(o.id);
            if(c!=null && !Objects.equals(c.authorization.fence,o.fence)) throw error("WRONG_FENCE","Google dispatch completion ownership mismatch");
            if(o.state.equals("RESERVED")) { release=Math.addExact(release,o.cost); updates.add(new Object[]{"RELEASED",Timestamp.from(time),o.id}); }
            else if(o.state.equals("DISPATCH_AUTHORIZED")||o.state.equals("UNKNOWN")) {
                updates.add(new Object[]{c==null ? "UNKNOWN" : c.succeeded ? "SUCCEEDED":"FAILED",Timestamp.from(time),o.id});
            }
        }
        List<Object> args=new ArrayList<>();String outcomes;
        if(updates.isEmpty()) outcomes="SELECT NULL::uuid AS id WHERE false";
        else {
            String values=String.join(",",Collections.nCopies(updates.size(),"(?::text,?::timestamptz,?::uuid)"));
            updates.forEach(row->Collections.addAll(args,row));
            outcomes="UPDATE explore_provider_usage_operations o SET state=v.state,completed_at=v.at FROM (VALUES "+values+") v(state,at,id) WHERE o.id=v.id RETURNING o.id";
        }
        Collections.addAll(args,release,r.hour,r.day,r.month,updates.size(),recovery ? "ABANDONED":"CLOSED",Timestamp.from(time),r.id);
        int changed=jdbc.update("WITH outcomes AS ("+outcomes+"), counters AS (UPDATE explore_provider_usage_windows SET reserved_micros=reserved_micros-? WHERE id IN(?,?,?) AND (SELECT count(*) FROM outcomes)=? RETURNING id) UPDATE explore_provider_usage_reservations SET state=CASE WHEN state='OPEN' THEN ? ELSE state END,settled_at=CASE WHEN state='OPEN' THEN ? ELSE settled_at END WHERE id=? AND (SELECT count(*) FROM counters)=3",args.toArray());
        if(changed!=1) throw error("LEDGER_INVALID","Settlement/counter batch was incomplete; transaction rolled back.");
    }
    private void recoverPeriodically() {
        long time=System.nanoTime(), last=lastRecovery.get();
        if((last==0 || time-last>5_000_000_000L)&&lastRecovery.compareAndSet(last,time)) recoverExpired();
    }
    public int recoverExpired() {
        final List<UUID> candidates;
        long scanStart=System.nanoTime();
        try {
            // Candidate scan has NO row locks. One stable scope only, bounded to ten phases.
            candidates=jdbc.query("SELECT r.id FROM explore_provider_usage_reservations r JOIN explore_provider_usage_windows w ON w.id=r.monthly_window_id WHERE w.budget_scope=? AND r.state='OPEN' AND r.expires_at<=clock_timestamp() ORDER BY r.expires_at,r.id LIMIT 10",(rs,n)->rs.getObject(1,UUID.class),scope);
        } catch(RuntimeException failure) { throw error("ACCOUNTING_UNAVAILABLE","Durable Google accounting recovery is unavailable; no paid admission is permitted."); }
        finally { metrics.computeIfAbsent("recoveryScan",k->new Stats()).add(System.nanoTime()-scanStart,false); }
        if(candidates.isEmpty()) return 0;
        return tx("recovery",()->{
            lockScope();
            // Bulk-load the complete lock set before taking any reservation/operation lock.
            String placeholders=String.join(",",Collections.nCopies(candidates.size(),"?"));
            String reservationSql="SELECT * FROM explore_provider_usage_reservations WHERE id IN("+placeholders+") ORDER BY id";
            org.springframework.jdbc.core.RowMapper<Reservation> mapper=(rs,n)->new Reservation(rs.getObject("id",UUID.class),rs.getObject("policy_revision_id",UUID.class),rs.getLong("hourly_window_id"),rs.getLong("daily_window_id"),rs.getLong("monthly_window_id"),rs.getString("state"),rs.getTimestamp("expires_at").toInstant(),rs.getString("operation_plan_digest"));
            var reads=jdbc.query(reservationSql,mapper,candidates.toArray());
            var ids=reads.stream().flatMap(r->r.windows().stream()).distinct().sorted().toList();
            String windowSql=String.join(",",Collections.nCopies(ids.size(),"?"));
            List<Object> windowArguments=new ArrayList<>();windowArguments.add(scope);windowArguments.addAll(ids);
            var locked=jdbc.queryForList("SELECT id FROM explore_provider_usage_windows WHERE budget_scope=? AND id IN("+windowSql+") ORDER BY id FOR UPDATE",windowArguments.toArray());
            if(locked.size()!=ids.size()) throw error("LEDGER_INVALID","Recovery accounting scope mismatch.");
            var reservations=jdbc.query(reservationSql+" FOR UPDATE",mapper,candidates.toArray());
            Map<UUID,List<Operation>> grouped=new HashMap<>();
            jdbc.query("SELECT * FROM explore_provider_usage_operations WHERE reservation_id IN("+placeholders+") ORDER BY reservation_id,id FOR UPDATE",rs->{
                var operation=new Operation(rs.getObject("id",UUID.class),rs.getString("operation_key"),rs.getString("price_book_version"),rs.getString("operation_kind"),rs.getString("sku"),rs.getInt("billable_units"),rs.getLong("unit_price_micros"),rs.getLong("estimated_cost_micros"),rs.getString("state"),rs.getObject("dispatch_fence",UUID.class));
                grouped.computeIfAbsent(rs.getObject("reservation_id",UUID.class),ignored->new ArrayList<>()).add(operation);
            },candidates.toArray());
            Instant time=now();int recovered=0;
            for(var r:reservations) if(r.state.equals("OPEN")&&!time.isBefore(r.expires)) {
                reconcile(r,grouped.getOrDefault(r.id,List.of()),List.of(),true,time);recovered++;
            }
            return recovered;
        });
    }
    public UUID applyApprovedPolicy(ApprovedPolicy change) {
        if(change==null||change.revisionId==null||change.effectiveAt==null||change.validUntil==null
                ||change.mode==null
                ||(change.mode==AccountingMode.TRACK_ONLY && (change.hourlyMicros!=0||change.dailyMicros!=0||change.monthlyMicros!=0))
                ||(change.mode==AccountingMode.OPTIONAL_HARD_CAP && (change.hourlyMicros<=0||change.dailyMicros<=0||change.monthlyMicros<=0))
                ||!change.validUntil.isAfter(change.effectiveAt)||change.approvedBy==null||change.approvedBy.isBlank()||change.approvedBy.length()>160
                ||change.approvalReference==null||change.approvalReference.isBlank()||change.approvalReference.length()>160
                ||change.reason==null||change.reason.isBlank()||change.reason.length()>500||change.holdTargets==null
                ||change.holdTargets.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||e.getValue()<0))
            throw new IllegalArgumentException("Invalid approved Google policy command");
        // Defensive snapshot: a caller cannot mutate a reviewed hold map while the transaction runs.
        var holds=Map.copyOf(change.holdTargets);
        Instant effective=change.effectiveAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant validUntil=change.validUntil.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return tx("policyActivation",()->{
            jdbc.update("INSERT INTO explore_provider_usage_policies(budget_scope) VALUES(?) ON CONFLICT DO NOTHING",scope);
            UUID parent=jdbc.queryForObject("SELECT active_revision_id FROM explore_provider_usage_policies WHERE budget_scope=? FOR UPDATE",UUID.class,scope);
            if(Objects.equals(parent,change.revisionId)) {
                var replay=jdbc.queryForMap("SELECT * FROM explore_provider_usage_policy_revisions WHERE id=? AND budget_scope=?",parent,scope);
                var recorded=tools.jackson.databind.json.JsonMapper.builder().build().readTree(replay.get("window_changes").toString());
                Map<Long,Long> recordedHolds=new HashMap<>();
                for(var item:recorded) if(item.has("requestedHold")&&!item.path("requestedHold").isNull()) recordedHolds.put(item.path("id").asLong(),item.path("requestedHold").asLong());
                if(!Objects.equals(replay.get("previous_revision_id"),change.expectedParent)
                        ||!GoogleRateBook.VERSION.equals(replay.get("price_book_version"))||!GoogleRateBook.DIGEST.equals(replay.get("price_book_digest"))
                        ||((Number)replay.get("protocol_version")).intValue()!=1
                        ||((Number)replay.get("hourly_limit_micros")).longValue()!=change.hourlyMicros
                        ||((Number)replay.get("daily_limit_micros")).longValue()!=change.dailyMicros
                        ||((Number)replay.get("monthly_limit_micros")).longValue()!=change.monthlyMicros
                        ||!Objects.equals(replay.get("accounting_mode"),change.mode.name())
                        ||!Objects.equals(replay.get("admissions_enabled"),change.enabled)
                        ||!((Timestamp)replay.get("effective_at")).toInstant().equals(effective)
                        ||!((Timestamp)replay.get("admission_valid_until")).toInstant().equals(validUntil)
                        ||!Objects.equals(replay.get("approved_by"),change.approvedBy)||!Objects.equals(replay.get("approval_reference"),change.approvalReference)
                        ||!Objects.equals(replay.get("reason"),change.reason)||!recordedHolds.equals(holds))
                    throw error("POLICY_CAS_CONFLICT","Policy revision replay does not match the approved command.");
                return parent;
            }
            if(!Objects.equals(parent,change.expectedParent)) throw error("POLICY_CAS_CONFLICT","Google policy changed since approval; no update was applied.");
            Instant time=now();
            if(time.isBefore(effective)||!time.isBefore(validUntil)) throw error("PRICE_BOOK_EXPIRED","Policy activation requires a currently valid reviewed pricing interval.");
            var windows=jdbc.queryForList("SELECT * FROM explore_provider_usage_windows WHERE budget_scope=? AND ends_at>? ORDER BY id FOR UPDATE",scope,Timestamp.from(time));
            if(!windows.stream().map(w->((Number)w.get("id")).longValue()).collect(java.util.stream.Collectors.toSet()).containsAll(holds.keySet()))
                throw error("POLICY_CAS_CONFLICT","Approved hold targets do not belong to this scope's active calendar windows.");
            List<Map<String,Object>> audit=new ArrayList<>();List<Object[]> updates=new ArrayList<>();
            for(var w:windows) {
                long id=((Number)w.get("id")).longValue();
                long limit=switch(w.get("policy_key").toString()) {case "GOOGLE_UTC_HOUR" -> change.hourlyMicros;case "GOOGLE_UTC_DAY" -> change.dailyMicros;case "GOOGLE_UTC_MONTH" -> change.monthlyMicros;default -> throw error("LEDGER_INVALID","Unexpected accounting window type");};
                long hold=holds.getOrDefault(id,((Number)w.get("uncertainty_hold_micros")).longValue());
                var entry=new LinkedHashMap<String,Object>();entry.put("id",id);entry.put("oldLimit",w.get("limit_micros"));entry.put("newLimit",limit);
                entry.put("oldHold",w.get("uncertainty_hold_micros"));entry.put("newHold",hold);entry.put("requestedHold",holds.get(id));
                entry.put("reserved",w.get("reserved_micros"));entry.put("charged",w.get("charged_micros"));audit.add(entry);
                updates.add(new Object[]{limit,hold,change.revisionId,id});
            }
            String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(audit);
            jdbc.update("INSERT INTO explore_provider_usage_policy_revisions(id,budget_scope,previous_revision_id,protocol_version,price_book_version,price_book_digest,hourly_limit_micros,daily_limit_micros,monthly_limit_micros,admissions_enabled,effective_at,admission_valid_until,approved_by,approval_reference,reason,window_changes,accounting_mode) VALUES(?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",change.revisionId,scope,parent,GoogleRateBook.VERSION,GoogleRateBook.DIGEST,change.hourlyMicros,change.dailyMicros,change.monthlyMicros,change.enabled,Timestamp.from(effective),Timestamp.from(validUntil),change.approvedBy,change.approvalReference,change.reason,json,change.mode.name());
            if(!updates.isEmpty()) jdbc.batchUpdate("UPDATE explore_provider_usage_windows SET limit_micros=?,uncertainty_hold_micros=?,applied_policy_revision_id=? WHERE id=?",updates);
            jdbc.update("UPDATE explore_provider_usage_policies SET active_revision_id=? WHERE budget_scope=?",change.revisionId,scope);
            return change.revisionId;
        });
    }
    private static GoogleAccountingException error(String code,String detail) { return new GoogleAccountingException(code,detail); }
}
