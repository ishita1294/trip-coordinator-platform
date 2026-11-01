package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.ishita.tripcoordinatorplatform.ai.document.SourceBookingExtractionV1.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticFact.Status.*;

/** Explicit reader only. Does not select a worker route, emit V3, persist, or authorize confirmation. */
public final class SourceBookingReaderV1 {
    public record Projected(Target field, SemanticTourExtraction.LocalMoment moment,
            SemanticTemporalConstraintV3.Relation relation, boolean inclusive,
            TemporalResolution temporalResolution, Instant instant) {}
    public enum TemporalResolution { UNIQUE_INSTANT, UNRESOLVED_TIMEZONE, DST_GAP, DST_OVERLAP }
    public record ComputedQuantity(QuantityKind kind, Integer clauseIndex, int value, SemanticFactV3.TemporalBasis basis) {}
    public record BookingView(int index, List<Projected> projections, List<ComputedQuantity> quantities, List<String> equivalentScheduleIds) {
        public BookingView { projections=List.copyOf(projections); quantities=List.copyOf(quantities); equivalentScheduleIds=List.copyOf(equivalentScheduleIds); }
        public BookingView(int index,List<Projected> projections,List<ComputedQuantity> quantities) {this(index,projections,quantities,List.of());}
    }
    public record Read(SourceBookingExtractionV1 facts, String sourceSha256, List<BookingView> bookings,
            int textMatchedSources, int unverifiedSources) { public Read { bookings=List.copyOf(bookings); } }

    public Read read(String json, JsonMapper mapper, DocumentAiInput original) {
        require(json!=null && json.length()<=100_000,"Source-fact response exceeds existing storage bound");
        require(original!=null && original.content()!=null && original.content().length>0,"Original source is required");
        try {
            var strict=mapper.rebuild().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
            var tree=strict.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            var result=strict.readerFor(SourceBookingExtractionV1.class).with(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .<SourceBookingExtractionV1>readValue(withUnboundLegacySchedules(tree).toString());
            return new Validation(result,new DocumentAiInput(original.originalFileName(),original.contentType(),original.content().clone())).read();
        } catch (InvalidDocumentExtractionException invalid) { throw invalid; }
        catch (tools.jackson.core.JacksonException | NullPointerException | IllegalArgumentException | ArithmeticException | DateTimeException invalid) {
            throw new InvalidDocumentExtractionException("Invalid source-booking extraction");
        }
    }
    /** Read-only compatibility: absence means UNBOUND, never an inferred activity/conversion relationship. */
    static tools.jackson.databind.JsonNode withUnboundLegacySchedules(tools.jackson.databind.JsonNode tree) {
        var copy=tree.deepCopy();
        var facts=copy.has("facts")?copy.path("facts"):copy;
        for(var booking:facts.path("bookings"))for(var claim:booking.path("schedules"))
            if(claim.isObject() && !claim.has("binding"))((tools.jackson.databind.node.ObjectNode)claim).putNull("binding");
        return copy;
    }
    private static void require(boolean valid,String reason) { if(!valid)throw new InvalidDocumentExtractionException(reason); }
    private static boolean text(String s) { return s!=null && !s.isBlank() && s.length()<=2000; }
    private static void bounded(List<?> list,int max) { require(list!=null && list.size()<=max,"Missing or oversized collection"); }

    private static final class Validation {
        final SourceBookingExtractionV1 extraction;
        final DocumentAiInput original;
        final Map<Integer,Source> sources=new HashMap<>();
        final Set<Integer> used=new HashSet<>();
        final Map<Integer,Clause> clauses=new HashMap<>();
        Validation(SourceBookingExtractionV1 extraction,DocumentAiInput original){this.extraction=extraction;this.original=original;}
        void refs(List<Integer> refs,boolean required) {
            bounded(refs,32); require(!required || !refs.isEmpty(),"Established claim requires source evidence");
            require(new HashSet<>(refs).size()==refs.size(),"Duplicate source reference");
            for(var id:refs) {require(id!=null && sources.containsKey(id),"Unknown source reference");used.add(id);}
        }
        <T> void fact(Fact<T> f,Consumer<T> value) { fact(f,value,false); }
        <T> void fact(Fact<T> f,Consumer<T> value,boolean calculated) {
            require(f!=null && f.status()!=null,"Explicit fact status is required");bounded(f.alternatives(),16);
            refs(f.sources(),f.status()!=MISSING && !calculated);
            if(f.status()==CLEAR)require(f.value()!=null && f.alternatives().isEmpty(),"Clear fact must have one value");
            else {require(f.value()==null,"Unresolved fact must not select a value");
                if(f.status()==MISSING)require(f.alternatives().isEmpty(),"Missing fact cannot have alternatives");
                else require(text(f.uncertainty()),"Ambiguous/conflicting fact needs uncertainty");}
            if(f.value()!=null)value.accept(f.value());for(var alternative:f.alternatives()){require(alternative!=null,"Null alternative");value.accept(alternative);}
        }
        void scope(Scope s) {
            require(s!=null && s.kind()!=null,"Scope is required");bounded(s.bookings(),12);
            require(new HashSet<>(s.bookings()).size()==s.bookings().size(),"Duplicate applicability target");
            require(s.kind()!=ScopeKind.BOOKINGS?s.bookings().isEmpty():!s.bookings().isEmpty(),"Invalid applicability kind");
            for(var i:s.bookings())require(i!=null && i>=0 && i<extraction.bookings().size(),"Unknown applicability target");
        }
        void condition(SemanticAssertionV3.Conditionality c,List<Integer> refs,boolean clear) {
            require(c!=null,"Condition decision required");refs(refs,c==SemanticAssertionV3.Conditionality.CONDITIONAL);
            require(c==SemanticAssertionV3.Conditionality.CONDITIONAL || refs.isEmpty(),"Unexpected condition passage");
            require(!clear || c!=SemanticAssertionV3.Conditionality.UNRESOLVED,"Unresolved condition cannot be clear");
        }
        Read read() {
            require(extraction!=null && extraction.schemaVersion()==SCHEMA_VERSION && CONTRACT.equals(extraction.contract()),"Wrong source-facts contract");
            bounded(extraction.sources(),512);bounded(extraction.bookings(),12);bounded(extraction.clauses(),160);
            require(!extraction.bookings().isEmpty(),"Booking inventory is required");
            require(extraction.coverageWarning()==null || text(extraction.coverageWarning()),"Invalid coverage warning");
            var reader=SemanticTourSourceEvidence.read(original);int matched=0,unverified=0;
            for(var s:extraction.sources()) {
                require(s!=null && s.id()>=0 && s.quotation()!=null && !s.quotation().isBlank() && s.quotation().length()<=8000
                        && sources.putIfAbsent(s.id(),s)==null,"Invalid/duplicate source passage");
                var check=reader.validateQuotation(new SemanticFactV3.SourceEvidence(null,null,s.quotation(),s.page()));
                if(check==SemanticTourSourceEvidence.QuotationCheck.TEXT_MATCHED)matched++;else unverified++;
            }
            for(int i=0;i<extraction.clauses().size();i++) {
                var c=extraction.clauses().get(i);require(c!=null && c.index()==i && c.kind()!=null && c.status()!=null && c.status()!=MISSING,"Invalid clause identity");
                refs(c.sources(),true);refs(c.qualificationSources(),false);fact(c.applicability(),this::scope);
                condition(c.conditionality(),c.conditionSources(),c.status()==CLEAR);
                require(c.status()==CLEAR || text(c.uncertainty()),"Unresolved clause needs uncertainty");clauses.put(i,c);
            }
            for(var booking:extraction.bookings())if(booking!=null && booking.schedules()!=null
                    && booking.schedules().stream().filter(Objects::nonNull).anyMatch(c->c.binding()!=null))
                for(var claim:booking.schedules())require(claim!=null && claim.binding()!=null,
                        "Bound booking requires explicit binding facts on every claim");
            var views=new ArrayList<BookingView>();
            for(int i=0;i<extraction.bookings().size();i++) {
                var b=extraction.bookings().get(i);require(b!=null && b.index()==i,"Booking source order is required");
                Consumer<String> string=s->require(text(s),"Invalid typed text");
                fact(b.name(),string);fact(b.provider(),string);fact(b.reference(),string);
                fact(b.timezone(),s->require(ZoneId.getAvailableZoneIds().contains(s),"Invalid IANA timezone"));
                fact(b.meetingPoint(),this::location);fact(b.endingPoint(),e->{require(e.meaning()!=null,"Missing endpoint meaning");
                    if(e.meaning()==SemanticTourExtraction.EndingMeaning.DIFFERENT_LOCATION){require(e.location()!=null,"Missing ending location");location(e.location());}
                    else require(e.location()==null,"Unexpected ending address");});
                fact(b.lifecycle(),s->require(s!=null && (b.lifecycle().status()!=CLEAR || s!=SemanticBookingLifecycleV3.State.UNKNOWN),"Unknown lifecycle is unresolved"));
                bounded(b.replaces(),12);for(var r:b.replaces())fact(r,a->{require(a.bookingIndex()!=null && a.bookingIndex()>=0 && a.bookingIndex()<extraction.bookings().size() && a.bookingIndex()!=b.index(),"Invalid replacement target");
                    var reference=extraction.bookings().get(a.bookingIndex()).reference();
                    if(a.bookingReference()!=null)require(reference!=null && reference.status()==CLEAR && a.bookingReference().equals(reference.value()),"Replacement reference disagrees with its target");});
                bounded(b.participants(),16);require(!b.participants().isEmpty(),"Explicit participant information or missing facts required");for(var p:b.participants()){fact(p.category(),string);fact(p.count(),n->require(n>0 && n<=100000,"Invalid participant count"));}
                views.add(new Schedules(b).view());
            }
            for(var b:extraction.bookings())visit(b.index(),new HashSet<>(),new HashSet<>());
            require(used.equals(sources.keySet()),"Unused source passages must not hide unassociated material");
            return new Read(extraction,TourExtractionCodec.sourceSha256(original.content()),views,matched,unverified);
        }
        void visit(int i,Set<Integer> path,Set<Integer> done) {
            if(done.contains(i))return;require(path.add(i),"Cyclic replacement relationships");
            for(var r:extraction.bookings().get(i).replaces())if(r.status()==CLEAR) {
                var target=extraction.bookings().get(r.value().bookingIndex());
                if(target.lifecycle().status()==CLEAR)require(target.lifecycle().value()==SemanticBookingLifecycleV3.State.CANCELLED
                        || target.lifecycle().value()==SemanticBookingLifecycleV3.State.SUPERSEDED,"Replacement contradicts target lifecycle");
                visit(target.index(),path,done);
            }
            path.remove(i);done.add(i);
        }
        void location(SemanticTourExtraction.DocumentLocation l) {
            require(l!=null && (text(l.name()) || text(l.address())),"Location needs a source-supported name/address");
            require((l.latitude()==null)==(l.longitude()==null),"Incomplete coordinate pair");
            if(l.latitude()!=null)require(Double.isFinite(l.latitude()) && Double.isFinite(l.longitude())
                    && Math.abs(l.latitude())<=90 && Math.abs(l.longitude())<=180,"Invalid source coordinates");
        }
        final class Schedules {
            final Booking booking;final Map<String,ScheduleClaim> schedules=new HashMap<>();final Set<String> resolving=new HashSet<>();
            Schedules(Booking booking){this.booking=booking;}
            void applicable(Integer clauseIndex,boolean established) {
                var c=clauses.get(clauseIndex);require(c!=null,"Unknown clause reference");
                var app=c.applicability();
                if(app.status()==CLEAR)require(app.value().kind()==ScopeKind.ALL_BOOKINGS
                        || app.value().kind()==ScopeKind.BOOKINGS && app.value().bookings().contains(booking.index()),
                        "Clause does not apply to this booking");
                else require(!established,"Uncertain applicability cannot establish a booking-local schedule/quantity");
            }
            BookingView view() {
                bounded(booking.schedules(),32);bounded(booking.projections(),3);bounded(booking.quantities(),24);
                for(var s:booking.schedules())require(s!=null && text(s.id()) && s.target()!=null && schedules.putIfAbsent(s.id(),s)==null,"Duplicate/invalid schedule identity");
                for(var s:booking.schedules()) {
                    if(s.target()==Target.CLAUSE_ACTION)applicable(s.clauseIndex(),s.fact()!=null && s.fact().status()==CLEAR);else require(s.clauseIndex()==null,"Unexpected schedule clause link");
                    fact(s.fact(),v->schedule(v,s.fact().status()==CLEAR));
                    binding(s);
                }
                for(var target:List.of(Target.START,Target.END,Target.REQUIRED_ARRIVAL))require(booking.schedules().stream().anyMatch(s->s.target()==target),"Missing explicit schedule target (including missing claims)");
                var equivalent=new ArrayList<String>();
                for(var claim:booking.schedules())if(verifiedEquivalent(claim))equivalent.add(claim.id());
                var projections=new ArrayList<Projected>();var fields=new HashSet<Target>();
                for(var p:booking.projections()) {
                    require(p!=null && p.field()!=null && p.field()!=Target.CLAUSE_ACTION && fields.add(p.field()),"Duplicate/invalid scalar projection");
                    var s=schedules.get(p.schedule());require(s!=null && s.target()==p.field() && s.fact().status()==CLEAR,"Projection requires established same-target claim");
                    require(primaryClaims(p.field(),equivalent).size()==1 && !equivalent.contains(s.id()),"Competing schedule claims cannot select a scalar");
                    require(s.binding()==null || s.binding().role().status()==CLEAR && s.binding().role().value()==ScheduleRole.ACTIVITY_SCHEDULE,
                            "Unresolved schedule role cannot select a scalar");
                    var v=s.fact().value();require(v.conditionality()==SemanticAssertionV3.Conditionality.UNCONDITIONAL,"Conditional schedule cannot project a scalar");
                    require(v.relation()==SemanticTemporalConstraintV3.Relation.EXACT && p.edge()==Edge.POINT
                            || p.field()==Target.REQUIRED_ARRIVAL && v.relation()==SemanticTemporalConstraintV3.Relation.DEADLINE && p.edge()==Edge.UPPER && v.upper().inclusive(),"Unsafe scalar projection");
                    var moment=resolve(p.schedule(),p.edge());var resolution=TemporalResolution.UNRESOLVED_TIMEZONE;Instant instant=null;
                    if(claimZone(s)!=null) {
                        var offsets=claimZone(s).getRules().getValidOffsets(moment);
                        resolution=offsets.isEmpty()?TemporalResolution.DST_GAP:offsets.size()>1?TemporalResolution.DST_OVERLAP:TemporalResolution.UNIQUE_INSTANT;
                        if(offsets.size()==1)instant=moment.toInstant(offsets.getFirst());
                    }
                    projections.add(new Projected(p.field(),SemanticTourExtraction.LocalMoment.of(moment),v.relation(),true,resolution,instant));
                }
                for(var kind:List.of(QuantityKind.DURATION_MINUTES,QuantityKind.ARRIVAL_LEAD_MINUTES))
                    require(booking.quantities().stream().anyMatch(q->q!=null && q.kind()==kind && q.clauseIndex()==null),"Missing explicit numeric schedule fact");
                var quantities=new ArrayList<ComputedQuantity>();var kinds=new HashSet<String>();
                for(var q:booking.quantities()) {
                    require(q!=null && q.kind()!=null && kinds.add(q.kind()+":"+q.clauseIndex()),"Duplicate/invalid quantity");
                    if(q.kind()==QuantityKind.MINIMUM_AGE_YEARS || q.kind()==QuantityKind.CANCELLATION_NOTICE_MINUTES)applicable(q.clauseIndex(),q.fact()!=null && q.fact().status()==CLEAR);
                    else require(q.clauseIndex()==null,"Unexpected quantity clause");
                    if(q.kind()==QuantityKind.CANCELLATION_NOTICE_MINUTES)require(clauses.get(q.clauseIndex()).kind()==ClauseKind.CANCELLATION_POLICY,"Cancellation notice cannot describe participation requirements");
                    boolean calculated=q.fact()!=null && q.fact().value()!=null && q.fact().value().difference()!=null;
                    fact(q.fact(),v->number(q,v),calculated);
                    if(q.fact().status()==CLEAR) {
                        var v=q.fact().value();var d=v.difference();int n=v.literal()!=null?v.literal():difference(d);
                        require(n>=0,"Negative quantity");quantities.add(new ComputedQuantity(q.kind(),q.clauseIndex(),n,d==null?null:d.basis()));
                    }
                }
                return new BookingView(booking.index(),projections,quantities,equivalent);
            }
            void number(Quantity q,NumberValue n) {
                require((n.literal()==null)!=(n.difference()==null),"Quantity must be literal OR declared arithmetic");
                if(n.literal()!=null)require(n.literal()>=0 && n.literal()<=1000000,"Invalid literal quantity");
                else {require(q.kind()==QuantityKind.DURATION_MINUTES || q.kind()==QuantityKind.ARRIVAL_LEAD_MINUTES,"Policy arithmetic is not supported");
                    var d=n.difference();require(d.basis()!=null,"Arithmetic time basis required");
                    var from=schedules.get(d.fromSchedule());var to=schedules.get(d.toSchedule());
                    require(from!=null && to!=null && (q.kind()==QuantityKind.DURATION_MINUTES
                            ?from.target()==Target.START && to.target()==Target.END
                            :from.target()==Target.REQUIRED_ARRIVAL && to.target()==Target.START),"Arithmetic operand meaning mismatch");
                    arithmeticScalar(from,d.fromEdge());arithmeticScalar(to,d.toEdge());difference(d);}
            }
            void arithmeticScalar(ScheduleClaim s,Edge edge) {
                require(s.fact()!=null && s.fact().status()==CLEAR,"Unresolved arithmetic operand");
                var v=s.fact().value();
                require(v!=null && v.conditionality()==SemanticAssertionV3.Conditionality.UNCONDITIONAL,
                        "Conditional arithmetic operand");
                require(v.relation()==SemanticTemporalConstraintV3.Relation.EXACT && edge==Edge.POINT
                        || s.target()==Target.REQUIRED_ARRIVAL && v.relation()==SemanticTemporalConstraintV3.Relation.DEADLINE
                        && edge==Edge.UPPER && v.upper().inclusive(),"Boundary does not establish a scalar arithmetic operand");
                var equivalent=booking.schedules().stream().filter(this::verifiedEquivalent).map(ScheduleClaim::id).toList();
                require(primaryClaims(s.target(),equivalent).size()==1 && !equivalent.contains(s.id()),
                        "Competing schedule claims cannot establish arithmetic");
                if(s.binding()!=null) {
                    require(s.binding().role().status()==CLEAR && s.binding().role().value()==ScheduleRole.ACTIVITY_SCHEDULE,
                            "Arithmetic needs an activity-local schedule");
                    instant(resolve(s.id(),edge),s); // Bound arithmetic never overlooks a missing zone or DST ambiguity.
                }
            }
            int difference(Difference d) {
                var a=resolve(d.fromSchedule(),d.fromEdge());var b=resolve(d.toSchedule(),d.toEdge());
                var from=schedules.get(d.fromSchedule());var to=schedules.get(d.toSchedule());
                if(d.basis()==SemanticFactV3.TemporalBasis.WALL_CLOCK && (from.binding()!=null || to.binding()!=null))
                    require(claimZone(from)!=null && Objects.equals(claimZone(from),claimZone(to)),"Cross-zone wall-clock difference is undefined");
                var duration=d.basis()==SemanticFactV3.TemporalBasis.WALL_CLOCK?Duration.between(a,b):Duration.between(instant(a,schedules.get(d.fromSchedule())),instant(b,schedules.get(d.toSchedule())));
                require(duration.getNano()==0 && duration.getSeconds()%60==0,"Non-minute arithmetic");return Math.toIntExact(duration.toMinutes());
            }
            void schedule(ScheduleValue v,boolean clear) {
                require(v!=null && v.relation()!=null,"Schedule relation required");condition(v.conditionality(),v.conditionSources(),clear);
                switch(v.relation()) {
                    case EXACT -> require(v.point()!=null && v.lower()==null && v.upper()==null,"Exact schedule is represented once");
                    case DEADLINE -> require(v.point()==null && v.lower()==null && v.upper()!=null,"Deadline needs upper bound only");
                    case EARLIEST -> require(v.point()==null && v.lower()!=null && v.upper()==null,"Earliest needs lower bound only");
                    case WINDOW -> require(v.point()==null && v.lower()!=null && v.upper()!=null,"Window needs independent bounds");
                }
                if(v.point()!=null)moment(v.point(),clear);if(v.lower()!=null)moment(v.lower().moment(),clear);if(v.upper()!=null)moment(v.upper().moment(),clear);
                if(clear && v.relation()==SemanticTemporalConstraintV3.Relation.WINDOW)
                    require(evaluate(v.lower().moment(),null).isBefore(evaluate(v.upper().moment(),null)),"Window bounds reversed or empty");
            }
            void moment(Moment m,boolean clear) {
                require(m!=null && (m.literal()==null)!=(m.offset()==null),"Moment must be literal OR declared offset");
                if(m.literal()!=null) {
                    require(m.literal().date()!=null || m.literal().time()!=null,"Empty local moment");
                    require(!clear || m.literal().date()!=null && m.literal().time()!=null,"Partial schedule cannot be clear");
                } else {var o=m.offset();require(o.operation()!=null && o.basis()!=null && o.minutes()>=0 && o.minutes()<=1000000,"Invalid declared offset");
                    if(clear)resolve(o.schedule(),o.edge());}
            }
            LocalDateTime resolve(String id,Edge edge) {
                String key=id+":"+edge;require(resolving.add(key),"Cyclic temporal arithmetic");
                try {
                    var s=schedules.get(id);require(s!=null && edge!=null && s.fact()!=null && s.fact().status()==CLEAR,"Arithmetic requires established schedule operand");
                    var v=s.fact().value();require(v!=null && v.conditionality()==SemanticAssertionV3.Conditionality.UNCONDITIONAL,"Conditional operand cannot establish a time");
                    Moment m=switch(edge){case POINT->v.point();case LOWER->v.lower()==null?null:v.lower().moment();case UPPER->v.upper()==null?null:v.upper().moment();};
                    require(m!=null,"Missing arithmetic boundary");return evaluate(m,s);
                } finally {resolving.remove(key);}
            }
            LocalDateTime evaluate(Moment m,ScheduleClaim owner) {
                if(m.literal()!=null){require(m.literal().date()!=null && m.literal().time()!=null,"Incomplete arithmetic operand");return m.literal().toLocalDateTime();}
                var o=m.offset();var base=resolve(o.schedule(),o.edge());long minutes=o.operation()==OffsetOperation.ADD_MINUTES?o.minutes():-o.minutes();
                var reference=schedules.get(o.schedule());
                if(o.basis()==SemanticFactV3.TemporalBasis.WALL_CLOCK) {
                    if(owner!=null && (owner.binding()!=null || reference.binding()!=null))
                        require(Objects.equals(claimZone(owner),claimZone(reference)) && claimZone(owner)!=null,"Cross-zone wall-clock offset is undefined");
                    return base.plusMinutes(minutes);
                }
                var expected=instant(base,reference).plusSeconds(Math.multiplyExact(minutes,60));
                var zone=owner==null?zone():claimZone(owner);require(zone!=null,"Elapsed arithmetic requires established timezone");
                var local=expected.atZone(zone).toLocalDateTime();
                require(instant(local,owner).equals(expected),"Elapsed result needs an unrepresented offset");return local;
            }
            ZoneId zone() {require(booking.timezone().status()==CLEAR,"Elapsed arithmetic requires established timezone");return ZoneId.of(booking.timezone().value());}
            Instant instant(LocalDateTime local,ScheduleClaim claim) {
                var zone=claim==null?zone():claimZone(claim);require(zone!=null,"Schedule timezone is unresolved");
                var offsets=zone.getRules().getValidOffsets(local);require(offsets.size()==1,"DST gap/overlap is unresolved");return local.toInstant(offsets.getFirst());
            }
            ZoneId claimZone(ScheduleClaim claim) {
                if(claim.binding()==null)return booking.timezone().status()==CLEAR?ZoneId.of(booking.timezone().value()):null;
                var timezone=claim.binding().timezone();return timezone.status()==CLEAR?ZoneId.of(timezone.value()):null;
            }
            void binding(ScheduleClaim claim) {
                var binding=claim.binding();if(binding==null)return; // Historic result; not a relationship declaration.
                fact(binding.timezone(),z->require(ZoneId.getAvailableZoneIds().contains(z),"Invalid claim IANA timezone"));
                fact(binding.role(),r->require(r!=null,"Schedule role required"));
                refs(binding.relationshipSources(),false);
                if(binding.role().status()==CLEAR && binding.role().value()==ScheduleRole.TIMEZONE_EXPRESSION) {
                    var primary=schedules.get(binding.equivalentTo());
                    require(primary!=null && primary!=claim && primary.target()==claim.target() && Objects.equals(primary.clauseIndex(),claim.clauseIndex()),
                            "Timezone expression requires a same-target activity claim");
                    require(primary.binding()!=null && primary.binding().role().status()==CLEAR
                            && primary.binding().role().value()==ScheduleRole.ACTIVITY_SCHEDULE,"Expression must bind directly to activity schedule");
                    refs(binding.relationshipSources(),true);
                } else require(binding.equivalentTo()==null && binding.relationshipSources().isEmpty(),"Unestablished expression cannot assert equivalence");
                if(binding.role().status()==CLEAR && binding.role().value()==ScheduleRole.ACTIVITY_SCHEDULE
                        && binding.timezone().status()==CLEAR && booking.timezone().status()==CLEAR)
                    require(binding.timezone().value().equals(booking.timezone().value()),"Activity schedule zone conflicts with booking zone");
            }
            List<ScheduleClaim> primaryClaims(Target target,List<String> equivalent) {
                return booking.schedules().stream().filter(c->c.target()==target && c.fact().status()!=MISSING && !equivalent.contains(c.id())).toList();
            }
            Moment boundary(ScheduleValue value,Edge edge) {
                return switch(edge) {case POINT->value.point();case LOWER->value.lower().moment();case UPPER->value.upper().moment();};
            }
            boolean verifiedEquivalent(ScheduleClaim expression) {
                var binding=expression.binding();
                if(binding==null || binding.role().status()!=CLEAR || binding.role().value()!=ScheduleRole.TIMEZONE_EXPRESSION)return false;
                var primary=schedules.get(binding.equivalentTo());
                if(primary==null || expression.fact().status()!=CLEAR || primary.fact().status()!=CLEAR
                        || claimZone(expression)==null || claimZone(primary)==null)return false;
                var a=expression.fact().value();var b=primary.fact().value();
                require(a.relation()==b.relation() && a.conditionality()==b.conditionality()
                        && new HashSet<>(a.conditionSources()).equals(new HashSet<>(b.conditionSources())),"Equivalent schedule semantics disagree");
                var edges=switch(a.relation()) {case EXACT->List.of(Edge.POINT);case DEADLINE->List.of(Edge.UPPER);case EARLIEST->List.of(Edge.LOWER);case WINDOW->List.of(Edge.LOWER,Edge.UPPER);};
                for(var edge:edges) {
                    if(edge!=Edge.POINT)require((edge==Edge.LOWER?a.lower():a.upper()).inclusive()==(edge==Edge.LOWER?b.lower():b.upper()).inclusive(),"Equivalent boundary inclusivity disagrees");
                    var x=evaluate(boundary(a,edge),expression);var y=evaluate(boundary(b,edge),primary);
                    var xo=claimZone(expression).getRules().getValidOffsets(x);var yo=claimZone(primary).getRules().getValidOffsets(y);
                    if(xo.size()!=1 || yo.size()!=1)return false; // Gaps/overlaps never prove equivalence.
                    require(x.toInstant(xo.getFirst()).equals(y.toInstant(yo.getFirst())),"Declared timezone expression is not equivalent");
                }
                return true;
            }
        }
    }
}
