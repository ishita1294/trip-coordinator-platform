package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.Duration;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.ishita.tripcoordinatorplatform.ai.document.SourceBookingExtraction.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticFact.Status.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingEligibility.Disposition.*;

/** Inactive read-only consumer. Readiness is not authorization or permission to persist/confirm. */
public final class SourceBookingReviewV1 {
    private SourceBookingReviewV1() {}
    public enum Association { APPLIES, POSSIBLE, DOCUMENT_CONTEXT }
    public record Issue(String code, String field, boolean blocking) {}
    public record ClauseView(Clause original, List<Source> passages, List<Source> conditions, List<Source> qualifications) {
        public ClauseView { passages=List.copyOf(passages);conditions=List.copyOf(conditions);qualifications=List.copyOf(qualifications); }
    }
    public record ClauseLink(int clauseIndex, Association association) {}
    public record Eligibility(SemanticBookingEligibility.Disposition lifecycleDisposition,
            SemanticBookingLifecycleV3.State lifecycle, boolean reviewReady, List<Issue> issues) {
        public Eligibility { issues=List.copyOf(issues); }
    }
    public record BookingReview(Booking original, SourceBookingReaderV1.BookingView deterministic,
            List<ClauseLink> clauses, Eligibility eligibility) {
        public BookingReview { clauses=List.copyOf(clauses); }
    }
    public record Review(int schemaVersion, String contract, String sourceSha256,
            List<Source> sources, List<ClauseView> clauses, List<BookingReview> bookings,
            List<Integer> eligibleBookingIndexes, String coverageWarning,
            int textMatchedSources, int unverifiedSources, boolean confirmationSupported) {
        public Review { sources=List.copyOf(sources);clauses=List.copyOf(clauses);bookings=List.copyOf(bookings);eligibleBookingIndexes=List.copyOf(eligibleBookingIndexes); }
    }
    public record Selection(boolean reviewReady, List<Integer> bookingIndexes, List<Issue> issues) {
        public Selection { bookingIndexes=List.copyOf(bookingIndexes);issues=List.copyOf(issues); }
    }
    /** Validates through the existing source-facts reader. No V3 objects or legacy decoding are constructed. */
    public static Review read(String json, JsonMapper mapper, DocumentAiInput original) {
        var read=new SourceBookingReaderV1().read(json,mapper,original);var facts=read.facts();
        var sources=new HashMap<Integer,Source>();facts.sources().forEach(s->sources.put(s.id(),s));
        var clauses=facts.clauses().stream().map(c->new ClauseView(c,passages(c.sources(),sources),
                passages(c.conditionSources(),sources),passages(c.qualificationSources(),sources))).toList();
        var bookings=new ArrayList<BookingReview>();var eligible=new ArrayList<Integer>();
        for(var booking:facts.bookings()) {
            var links=new ArrayList<ClauseLink>();
            for(var clause:facts.clauses()) {
                var association=association(clause.applicability(),booking.index());
                if(association!=null)links.add(new ClauseLink(clause.index(),association));
            }
            var deterministic=read.bookings().get(booking.index());
            var eligibility=assess(facts,booking,deterministic,links,Map.of());
            bookings.add(new BookingReview(booking,deterministic,links,eligibility));
            if(eligibility.reviewReady())eligible.add(booking.index());
        }
        return new Review(SCHEMA_VERSION,CONTRACT,read.sourceSha256(),facts.sources(),clauses,bookings,eligible,
                facts.coverageWarning(),read.textMatchedSources(),read.unverifiedSources(),false);
    }
    public static Eligibility reassessLifecycle(Review review,int index,Map<Integer,SemanticBookingLifecycleV3.State> declaredStates) {
        var facts=new SourceBookingExtraction(SCHEMA_VERSION,CONTRACT,review.sources(),review.bookings().stream().map(BookingReview::original).toList(),
                review.clauses().stream().map(c->c.original()).toList(),review.coverageWarning());
        var b=review.bookings().get(index);
        return assess(facts,b.original(),b.deterministic(),b.clauses(),declaredStates);
    }
    private static List<Source> passages(List<Integer> ids,Map<Integer,Source> sources) {
        return ids.stream().map(sources::get).toList(); // Separate quotations remain separate, verbatim passages.
    }
    private static boolean applies(Scope scope,int booking) {
        return scope.kind()==ScopeKind.ALL_BOOKINGS || scope.kind()==ScopeKind.BOOKINGS && scope.bookings().contains(booking);
    }
    private static Association association(Fact<Scope> scope,int booking) {
        if(scope.status()==CLEAR)return scope.value().kind()==ScopeKind.DOCUMENT?Association.DOCUMENT_CONTEXT:
                applies(scope.value(),booking)?Association.APPLIES:null;
        // No candidate set means unknown applicability, never permission to silently discard the clause.
        if(scope.alternatives().isEmpty() || scope.alternatives().stream().anyMatch(s->s.kind()==ScopeKind.DOCUMENT || applies(s,booking)))
            return Association.POSSIBLE;
        return null;
    }
    private static boolean critical(ClauseKind kind) {
        return switch(kind) {case INSTRUCTION,REQUIREMENT,RESTRICTION,POLICY,CANCELLATION_POLICY->true;default->false;};
    }
    private static void block(List<Issue> issues,String code,String field) {issues.add(new Issue(code,field,true));}
    private static void uncertain(List<Issue> issues,Fact<?> fact,String field,boolean required) {
        if(required && fact.status()!=CLEAR || !required && (fact.status()==AMBIGUOUS || fact.status()==CONFLICTING))
            block(issues,"SEMANTIC_FACT_UNRESOLVED",field);
    }
    private static Eligibility assess(SourceBookingExtraction facts,Booking b,
            SourceBookingReaderV1.BookingView deterministic,List<ClauseLink> links,Map<Integer,SemanticBookingLifecycleV3.State> declaredStates) {
        var issues=new ArrayList<Issue>();
        var state=declaredStates.getOrDefault(b.index(),b.lifecycle().status()==CLEAR?b.lifecycle().value():SemanticBookingLifecycleV3.State.UNKNOWN);
        var disposition=switch(state) {case CANCELLED,SUPERSEDED->HISTORICAL;case ACTIVE->ACTIVE_ELIGIBLE;default->REQUIRES_REVIEW;};
        if(disposition==HISTORICAL)block(issues,"BOOKING_HISTORICAL","lifecycle");
        else if(disposition==REQUIRES_REVIEW)block(issues,"BOOKING_LIFECYCLE_UNESTABLISHED","lifecycle");
        if(state==SemanticBookingLifecycleV3.State.ACTIVE)for(var r:b.replaces()) {
            if(r.status()!=CLEAR)block(issues,"BOOKING_REPLACEMENT_UNRESOLVED","replaces");
            else {
                var prior=facts.bookings().get(r.value().bookingIndex()).lifecycle();
                var priorState=declaredStates.getOrDefault(r.value().bookingIndex(),prior.status()==CLEAR?prior.value():SemanticBookingLifecycleV3.State.UNKNOWN);
                if(priorState!=SemanticBookingLifecycleV3.State.CANCELLED && priorState!=SemanticBookingLifecycleV3.State.SUPERSEDED)
                    block(issues,"BOOKING_REPLACEMENT_UNRESOLVED","replaces");
            }
        }
        uncertain(issues,b.name(),"name",true);uncertain(issues,b.timezone(),"timezone",true);
        // Existing V3 policy allows unknown/uncertain optional provider/reference and unknown endpoints.
        textLimit(issues,b.name(),255,"name");textLimit(issues,b.provider(),255,"provider");textLimit(issues,b.reference(),255,"reference");
        uncertain(issues,b.meetingPoint(),"meetingPoint",false);
        if(b.endingPoint().status()==CONFLICTING)block(issues,"SEMANTIC_FACT_UNRESOLVED","endingPoint");
        if(b.endingPoint().status()==CLEAR && b.endingPoint().value().meaning()==SemanticTourExtraction.EndingMeaning.SAME_AS_MEETING_POINT && b.meetingPoint().status()!=CLEAR)
            block(issues,"INVALID_BOOKING_LOCATION","endingPoint");
        if(b.meetingPoint().status()==CLEAR)location(issues,b.meetingPoint().value(),"meetingPoint");
        if(b.endingPoint().status()==CLEAR && b.endingPoint().value().location()!=null)location(issues,b.endingPoint().value().location(),"endingPoint");
        var projected=new EnumMap<Target,SourceBookingReaderV1.Projected>(Target.class);
        deterministic.projections().forEach(p->projected.put(p.field(),p));
        for(var target:List.of(Target.START,Target.END,Target.REQUIRED_ARRIVAL)) {
            var claims=b.schedules().stream().filter(s->s.target()==target && s.fact().status()!=MISSING && !deterministic.equivalentScheduleIds().contains(s.id())).toList();
            if(claims.isEmpty()) {if(target==Target.START)block(issues,"MISSING_BOOKING_FIELDS","START");continue;}
            if(claims.size()!=1) {
                block(issues,claims.stream().anyMatch(c->c.binding()==null)?"SCHEDULE_BINDING_CLARIFICATION_REQUIRED":"SEMANTIC_FACT_UNRESOLVED",target.name());continue;
            }
            var claim=claims.getFirst();
            if(claim.fact().status()!=CLEAR) {block(issues,"SEMANTIC_FACT_UNRESOLVED",target.name());continue;}
            var value=claim.fact().value();
            boolean representable=value.conditionality()==SemanticAssertionV3.Conditionality.UNCONDITIONAL
                    && (target==Target.REQUIRED_ARRIVAL?value.relation()==SemanticTemporalConstraintV3.Relation.DEADLINE && value.upper().inclusive():value.relation()==SemanticTemporalConstraintV3.Relation.EXACT);
            if(!representable)block(issues,"TEMPORAL_CONSTRAINT_NOT_REPRESENTABLE",target.name());
            var p=projected.get(target);
            if(p==null)block(issues,"SCHEDULE_PROJECTION_REQUIRED",target.name());
            else if(p.temporalResolution()!=SourceBookingReaderV1.TemporalResolution.UNIQUE_INSTANT)
                block(issues,"AMBIGUOUS_BOOKING_TIME",target.name());
            // As in authoritativeV3, local arithmetic cannot be promoted to elapsed-authoritative timing.
            if(wallClock(claim.id(),b,new HashSet<>()))block(issues,"LOCAL_ARITHMETIC_NOT_AUTHORITATIVE",target.name());
        }
        for(var q:b.quantities()) {
            uncertain(issues,q.fact(),q.kind().name(),false);
            if(q.fact().status()==CLEAR && q.kind()==QuantityKind.DURATION_MINUTES && q.fact().value().literal()!=null && q.fact().value().literal()<=0)
                block(issues,"INVALID_BOOKING_DURATION","durationMinutes");
        }
        var start=projected.get(Target.START);var end=projected.get(Target.END);var arrival=projected.get(Target.REQUIRED_ARRIVAL);
        if(start!=null && start.instant()!=null) {
            if(end!=null && end.instant()!=null && end.instant().isBefore(start.instant()))block(issues,"INVALID_BOOKING_END","END");
            if(arrival!=null && arrival.instant()!=null) {
                long seconds=Duration.between(arrival.instant(),start.instant()).getSeconds();
                if(seconds<0 || seconds%60!=0 || seconds/60>Integer.MAX_VALUE || arrival.moment().time().getSecond()!=0 || arrival.moment().time().getNano()!=0)
                    block(issues,"INVALID_REQUIRED_ARRIVAL","REQUIRED_ARRIVAL");
            }
            for(var q:deterministic.quantities()) {
                if(q.clauseIndex()!=null || q.kind()!=QuantityKind.DURATION_MINUTES && q.kind()!=QuantityKind.ARRIVAL_LEAD_MINUTES)continue;
                if(q.kind()==QuantityKind.DURATION_MINUTES && q.value()<=0)block(issues,"INVALID_BOOKING_DURATION","durationMinutes");
                if(q.basis()==SemanticFactV3.TemporalBasis.WALL_CLOCK) {
                    issues.add(new Issue("LOCAL_ARITHMETIC_NOT_AUTHORITATIVE",q.kind().name(),false));continue;
                }
                if(q.kind()==QuantityKind.DURATION_MINUTES) {
                    if(end==null)block(issues,"END_PROJECTION_REQUIRED","END"); // Never infer an undeclared end.
                    else if(end.instant()!=null && !start.instant().plusSeconds(q.value()*60L).equals(end.instant()))block(issues,"CONFLICTING_BOOKING_END","END");
                } else if(arrival==null)block(issues,"ARRIVAL_PROJECTION_REQUIRED","REQUIRED_ARRIVAL");
                else if(arrival.instant()!=null && !start.instant().minusSeconds(q.value()*60L).equals(arrival.instant()))block(issues,"CONFLICTING_ARRIVAL_REQUIREMENTS","REQUIRED_ARRIVAL");
            }
        }
        for(var link:links) {
            var c=facts.clauses().get(link.clauseIndex());
            if(link.association()!=Association.DOCUMENT_CONTEXT && critical(c.kind())
                    && (link.association()==Association.POSSIBLE || c.status()!=CLEAR || c.conditionality()==SemanticAssertionV3.Conditionality.UNRESOLVED))
                block(issues,"MATERIAL_CLAUSE_REVIEW_REQUIRED","clauses["+c.index()+"]");
        }
        if(facts.coverageWarning()!=null)issues.add(new Issue("SOURCE_COVERAGE_UNCERTAIN","coverageWarning",false));
        return new Eligibility(disposition,state,disposition==ACTIVE_ELIGIBLE && issues.stream().noneMatch(Issue::blocking),issues);
    }
    private static boolean wallClock(String id,Booking b,Set<String> visited) {
        if(!visited.add(id))return true;
        var s=b.schedules().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();var value=s.fact().value();
        if(value==null)return false;
        for(var m:Arrays.asList(value.point(),value.lower()==null?null:value.lower().moment(),value.upper()==null?null:value.upper().moment()))
            if(m!=null && m.offset()!=null && (m.offset().basis()==SemanticFactV3.TemporalBasis.WALL_CLOCK || wallClock(m.offset().schedule(),b,new HashSet<>(visited))))return true;
        return false;
    }
    private static void textLimit(List<Issue> issues,Fact<String> f,int max,String field) {
        if(f.status()==CLEAR && f.value().length()>max)block(issues,"INVALID_BOOKING_FIELDS",field);
    }
    private static void location(List<Issue> issues,SemanticTourExtraction.DocumentLocation l,String field) {
        if(l.name()!=null && l.name().length()>255 || l.address()!=null && l.address().length()>1000)
            block(issues,"INVALID_BOOKING_LOCATION",field);
    }
}
