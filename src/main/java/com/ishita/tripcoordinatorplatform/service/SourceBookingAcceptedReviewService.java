package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Status.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SourceBookingExtraction.*;

/** Inactive native effective read; no route, AI call or reservation creation. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SourceBookingAcceptedReviewService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final TourBookingStateService bookingState;
    private final JsonMapper mapper;
    public SourceBookingAcceptedReviewService(TravelDocumentRepository documents,TravelDocumentExtractionRepository extractions,
            TourBookingStateService bookingState,JsonMapper mapper) {
        this.documents=documents;this.extractions=extractions;this.bookingState=bookingState;this.mapper=mapper;
    }
    public record Acceptance(Long reviewId,long version,Instant acceptedAt,int bookingIndex,String field,
            TourSourceAssessment.Outcome authority) {}
    public record BookingOverlay(int index,Map<String,JsonNode> currentValues,Set<String> correctedFields,
            Set<String> invalidatedFields,LifecycleClarification acceptedLifecycle,
            SourceBookingReview.Eligibility eligibility) {
        public BookingOverlay { currentValues=Map.copyOf(currentValues);correctedFields=Set.copyOf(correctedFields);invalidatedFields=Set.copyOf(invalidatedFields); }
    }
    public record Review(Long documentId,Long extractionId,String extractionSha256,String acceptedStateToken,
            SourceBookingReview.Review original,List<BookingOverlay> bookings,List<Acceptance> history,
            List<Integer> eligibleBookingIndexes,boolean confirmationSupported,SourceBookingReview.Review effectiveReview) {
        public Review { bookings=List.copyOf(bookings);history=List.copyOf(history);eligibleBookingIndexes=List.copyOf(eligibleBookingIndexes); }
    }
    /** original is supplied by the trusted storage caller; no endpoint accepts caller-provided extraction JSON. */
    @Transactional(readOnly=true)
    public Review read(Long tripId,Long documentId,Long actor,DocumentAiInput original) {
        var doc=documents.findByIdAndTrip_Id(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(actor==null || !Objects.equals(doc.getUploadedByUserId(),actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId)
                .orElseThrow(()->TourCorrectionException.stale("Source extraction unavailable."));
        return compose(doc,extraction,original,List.of());
    }
    /** Pending proposals are previewed separately from accepted history; never appended to the journal here. */
    Review compose(TravelDocument doc,TravelDocumentExtraction extraction,DocumentAiInput original,List<Change> pending) {
        return compose(doc,extraction,original,pending,null);
    }
    private Review compose(TravelDocument doc,TravelDocumentExtraction extraction,DocumentAiInput original,List<Change> pending,Instant cutoff) {
        var tree=mapper.readTree(extraction.getExtractedData());
        var base=StoredSourceBooking.isStored(tree)?StoredSourceBooking.read(extraction.getExtractedData(),mapper,original).review(mapper,original)
                :SourceBookingReview.read(extraction.getExtractedData(),mapper,original);
        var accepted=bookingState.accepted(doc,extraction).stream().filter(r->cutoff==null || !r.acceptedAt.isAfter(cutoff)).toList(); // Existing acceptedAt/id ordering.
        var merged=new LinkedHashMap<String,Change>();var history=new ArrayList<Acceptance>();var seen=new HashSet<Long>();
        String hash=TourCorrectionService.hash(extraction.getExtractedData());
        for(var review:accepted) {
            if(review.id==null || !seen.add(review.id) || review.version<1 || !Objects.equals(review.documentId,doc.getId())
                    || !Objects.equals(review.actorId,doc.getUploadedByUserId()) || !Objects.equals(review.acceptedBy,review.actorId)
                    || !Objects.equals(review.extractionSha256,hash) || !Objects.equals(review.documentSha256,base.sourceSha256()))
                throw TourCorrectionException.stale("Accepted correction source/ownership mismatch.");
            var changes=bookingState.changes(review);
            var assessment=NativeScheduleClarification.assessment(mapper.readValue(review.assessmentJson,TourSourceAssessment.class),changes);
            var findings=new HashMap<String,TourSourceAssessment.Finding>();
            for(var f:assessment.findings()) {
                if(f.outcome()!=TourSourceAssessment.Outcome.SOURCE_SUPPORTED_BY_AI_REVIEW && f.outcome()!=TourSourceAssessment.Outcome.USER_REPORTED_LIFECYCLE_CLARIFICATION && f.outcome()!=TourSourceAssessment.Outcome.USER_REPORTED_SCHEDULE_CLARIFICATION)
                    throw TourCorrectionException.validation("SOURCE_CORRECTION_NOT_SUPPORTED","Accepted review lacks supported assessment provenance.");
                findings.put(f.bookingIndex()+":"+f.field(),f);
            }
            for(var c:changes) {
                if(c==null || c.bookingIndex()==null || c.bookingIndex()<0 || c.bookingIndex()>=base.bookings().size()
                        || c.field()==null || !TourConfirmationPolicy.FIELDS.contains(c.field()) && !NativeScheduleClarification.FIELD.equals(c.field()) || c.originalValue()==null || c.proposedValue()==null)
                    throw TourCorrectionException.invalid("Invalid accepted booking field correction.");
                var originalValue=values(base.bookings().get(c.bookingIndex())).get(c.field());
                if(!canonical(c.field(),originalValue).equals(canonical(c.field(),c.originalValue())))
                    throw TourCorrectionException.stale("Original field value changed; reload the source review.");
                if(canonical(c.field(),originalValue).equals(canonical(c.field(),c.proposedValue())))
                    throw TourCorrectionException.invalid("Unchanged values are not corrections.");
                validateValue(c.field(),c.proposedValue());
                merged.put(c.bookingIndex()+":"+c.field(),c); // Last accepted field wins, never an extraction mutation.
                history.add(new Acceptance(review.id,review.version,review.acceptedAt,c.bookingIndex(),c.field(),findings.get(c.bookingIndex()+":"+c.field()).outcome()));
            }
        }
        for(var c:pending) {
            checkChange(base,c);
            merged.put(c.bookingIndex()+":"+c.field(),c);
        }
        var declarations=new HashMap<Integer,LifecycleClarification>();
        for(var c:merged.values())if(c.field().equals("lifecycle")) {
            var declaration=mapper.treeToValue(c.proposedValue(),LifecycleClarification.class);var source=base.bookings().get(c.bookingIndex()).original().lifecycle();
            if((source.status()==CONFLICTING || source.status()==CLEAR && source.value()!=declaration.state()) && !declaration.acknowledgeSourceConflict())
                throw TourCorrectionException.validation("LIFECYCLE_CONFLICT_ACKNOWLEDGEMENT_REQUIRED","Source conflict needs explicit acknowledgement.");
            declarations.put(c.bookingIndex(),declaration);
        }
        var states=new HashMap<Integer,SemanticBookingLifecycleV3.State>();declarations.forEach((i,d)->states.put(i,d.state()));
        for(var row:base.bookings())for(var link:row.original().replaces())if(link.status()==CLEAR) {
            var prior=base.bookings().get(link.value().bookingIndex()).original().lifecycle();
            var state=states.getOrDefault(link.value().bookingIndex(),prior.status()==CLEAR?prior.value():SemanticBookingLifecycleV3.State.UNKNOWN);
            if(states.containsKey(link.value().bookingIndex()) && state!=SemanticBookingLifecycleV3.State.CANCELLED && state!=SemanticBookingLifecycleV3.State.SUPERSEDED)
                throw TourCorrectionException.validation("INVALID_LIFECYCLE_CLARIFICATION","Established replacement requires an inactive predecessor.");
        }
        var rows=new ArrayList<BookingOverlay>();var effectiveRows=new ArrayList<SourceBookingReview.BookingReview>();var eligible=new ArrayList<Integer>();
        for(var row:base.bookings()) {
            int index=row.original().index();var values=values(row);var corrected=new HashSet<String>();
            for(var c:merged.values())if(c.bookingIndex()==index){values.put(c.field(),c.proposedValue().deepCopy());corrected.add(c.field());}
            var invalidated=new HashSet<String>();var effective=base;
            if(corrected.contains(NativeScheduleClarification.FIELD))effective=NativeScheduleClarification.apply(base,index,values.get(NativeScheduleClarification.FIELD),mapper,original);
            boolean temporalBlocked=false;
            if(corrected.stream().anyMatch(TEMPORAL::contains)) {
                try { effective=temporalReview(effective,effective.bookings().get(index),values,corrected,original); }
                catch(TourCorrectionException unsupported) { temporalBlocked=true; }
            }
            var assessed=SourceBookingReview.reassessLifecycle(effective,index,states);
            var issues=new ArrayList<>(assessed.issues());
            for(String field:corrected)if(!TEMPORAL.contains(field) && !field.equals("lifecycle") && !field.equals(NativeScheduleClarification.FIELD)) {
                issues.removeIf(i->field.equals(i.field()));
                var value=values.get(field);
                if(field.equals("name") && (value.isNull() || value.asText().isBlank()))block(issues,"MISSING_BOOKING_FIELDS",field);
            }
            if(temporalBlocked) {
                block(issues,"TEMPORAL_CORRECTION_REQUIRES_NATIVE_CONSTRAINT_REVIEW","schedule");
                for(String field:dependentFields(row.original(),corrected))
                    if(!corrected.contains(field)){values.put(field,mapper.nullNode());invalidated.add(field);}
            } else if(effective!=base) {
                var recomputed=values(effective.bookings().get(index));
                for(String field:TEMPORAL)if(!corrected.contains(field))values.put(field,recomputed.get(field));
            }
            endpoint(values,corrected,issues);
            var eligibility=new SourceBookingReview.Eligibility(assessed.lifecycleDisposition(),assessed.lifecycle(),
                    assessed.lifecycleDisposition()==BookingEligibility.Disposition.ACTIVE_ELIGIBLE && issues.stream().noneMatch(SourceBookingReview.Issue::blocking),issues);
            effectiveRows.add(effective.bookings().get(index));
            rows.add(new BookingOverlay(index,values,corrected,invalidated,declarations.get(index),eligibility));
            if(eligibility.reviewReady())eligible.add(index);
        }
        var effectiveReview=new SourceBookingReview.Review(base.schemaVersion(),base.contract(),base.sourceSha256(),base.sources(),base.clauses(),effectiveRows,eligible,base.coverageWarning(),base.textMatchedSources(),base.unverifiedSources(),false);
        return new Review(doc.getId(),extraction.getId(),hash,bookingState.token(accepted),base,rows,history,eligible,false,effectiveReview);
    }
    private void checkChange(SourceBookingReview.Review base,Change c) {
        if(c==null || c.bookingIndex()==null || c.bookingIndex()<0 || c.bookingIndex()>=base.bookings().size()
                || c.field()==null || !TourConfirmationPolicy.FIELDS.contains(c.field()) && !NativeScheduleClarification.FIELD.equals(c.field()) || c.originalValue()==null || c.proposedValue()==null)
            throw TourCorrectionException.invalid("Invalid native correction field.");
        var original=values(base.bookings().get(c.bookingIndex())).get(c.field());
        if(!canonical(c.field(),original).equals(canonical(c.field(),c.originalValue())))
            throw TourCorrectionException.stale("Original field changed; reload the immutable extraction.");
        if(canonical(c.field(),original).equals(canonical(c.field(),c.proposedValue())))
            throw TourCorrectionException.invalid("Unchanged values are not corrections.");
        validateValue(c.field(),c.proposedValue());
    }
    /** Transient effective native view only. Original claims/history are never rewritten or returned as corrected source. */
    private SourceBookingReview.Review temporalReview(SourceBookingReview.Review base,
            SourceBookingReview.BookingReview row,Map<String,JsonNode> values,Set<String> corrected,DocumentAiInput original) {
        var tree=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(new SourceBookingExtraction(
                SCHEMA_VERSION,CONTRACT,base.sources(),base.bookings().stream().map(SourceBookingReview.BookingReview::original).toList(),
                base.clauses().stream().map(SourceBookingReview.ClauseView::original).toList(),base.coverageWarning()));
        var booking=(tools.jackson.databind.node.ObjectNode)tree.path("bookings").get(row.original().index());
        for(String field:corrected)if(TEMPORAL.contains(field)) {
            var proposed=values.get(field);
            if(proposed.isNull())throw unsupportedTemporal();
            if(field.equals("timezone")) {
                // Do not erase source ambiguity or manufacture evidence for an unestablished native fact.
                if(row.original().timezone().status()!=CLEAR)throw unsupportedTemporal();
                ((tools.jackson.databind.node.ObjectNode)booking.path("timezone")).set("value",proposed);
            } else if(field.equals("durationMinutes") || field.equals("arrivalLeadMinutes")) {
                String kind=field.equals("durationMinutes")?"DURATION_MINUTES":"ARRIVAL_LEAD_MINUTES";
                var q=row.original().quantities().stream().filter(x->x.kind().name().equals(kind)&&x.clauseIndex()==null).findFirst().orElseThrow();
                // An elapsed scalar correction cannot replace a declared WALL_CLOCK/difference interpretation.
                if(q.fact().status()!=CLEAR || q.fact().value().literal()==null)throw unsupportedTemporal();
                for(var node:booking.path("quantities"))if(node.path("kind").asText().equals(kind)&&node.path("clauseIndex").isNull())
                    ((tools.jackson.databind.node.ObjectNode)node.path("fact").path("value")).set("literal",proposed);
            } else {
                Target target=field.startsWith("start")?Target.START:field.startsWith("end")?Target.END:Target.REQUIRED_ARRIVAL;
                var claims=row.original().schedules().stream().filter(x->x.target()==target&&x.fact().status()!=MISSING).toList();
                if(claims.size()!=1 || claims.getFirst().fact().status()!=CLEAR)throw unsupportedTemporal();
                var claim=claims.getFirst();var v=claim.fact().value();
                if(v.conditionality()!=SemanticAssertionV3.Conditionality.UNCONDITIONAL || row.original().projections().stream().noneMatch(x->x.field()==target))throw unsupportedTemporal();
                boolean arrival=target==Target.REQUIRED_ARRIVAL;
                if(arrival?v.relation()!=SemanticTemporalConstraintV3.Relation.DEADLINE||!v.upper().inclusive():v.relation()!=SemanticTemporalConstraintV3.Relation.EXACT)throw unsupportedTemporal();
                var moment=arrival?v.upper().moment():v.point();if(moment.literal()==null)throw unsupportedTemporal();
                for(var node:booking.path("schedules"))if(node.path("id").asText().equals(claim.id())) {
                    var value=node.path("fact").path("value");
                    var literal=(tools.jackson.databind.node.ObjectNode)(arrival?value.path("upper").path("moment"):value.path("point")).path("literal");
                    if(arrival) {var at=LocalDateTime.parse(proposed.asText());literal.put("date",at.toLocalDate().toString());literal.put("time",at.toLocalTime().toString());}
                    else literal.set(field.endsWith("Date")?"date":"time",proposed);
                }
            }
        }
        try{return SourceBookingReview.read(tree.toString(),mapper,original);}
        catch(InvalidDocumentExtractionException invalid){throw unsupportedTemporal();}
    }
    private static TourCorrectionException unsupportedTemporal(){return TourCorrectionException.validation(
            "TEMPORAL_CORRECTION_REQUIRES_NATIVE_CONSTRAINT_REVIEW","Correction cannot preserve the native schedule relation, evidence or arithmetic.");}
    private static final Set<String> TEMPORAL=Set.of("startDate","startTime","endDate","endTime","timezone","durationMinutes","arrivalLeadMinutes","requiredArrivalLocal");
    private Map<String,JsonNode> values(SourceBookingReview.BookingReview row) {
        var b=row.original();var out=new LinkedHashMap<String,JsonNode>();
        out.put("name",node(b.name()));out.put("provider",node(b.provider()));out.put("bookingReference",node(b.reference()));out.put("timezone",node(b.timezone()));
        out.put(NativeScheduleClarification.FIELD,mapper.createObjectNode());
        out.put("meetingPoint",node(b.meetingPoint()));out.put("lifecycle",mapper.valueToTree(b.lifecycle()));
        var ending=b.endingPoint().status()==CLEAR?b.endingPoint().value():null;
        out.put("endingLocationChoice",mapper.valueToTree(ending==null?"UNKNOWN":switch(ending.meaning()){case UNKNOWN->"UNKNOWN";case SAME_AS_MEETING_POINT->"SAME_AS_MEETING_POINT";case DIFFERENT_LOCATION->"LOCATION";}));
        out.put("endingPoint",mapper.valueToTree(ending==null?null:ending.location()));
        for(String field:List.of("startDate","startTime","endDate","endTime","requiredArrivalLocal","durationMinutes","arrivalLeadMinutes"))out.put(field,mapper.nullNode());
        for(var p:row.deterministic().projections())switch(p.field()) {
            case START->{out.put("startDate",mapper.valueToTree(p.moment().date()));out.put("startTime",mapper.valueToTree(p.moment().time()));}
            case END->{out.put("endDate",mapper.valueToTree(p.moment().date()));out.put("endTime",mapper.valueToTree(p.moment().time()));}
            case REQUIRED_ARRIVAL->out.put("requiredArrivalLocal",mapper.valueToTree(p.moment().toLocalDateTime().toString()));default->{}
        }
        for(var q:row.deterministic().quantities())if(q.clauseIndex()==null && q.basis()!=SemanticFactV3.TemporalBasis.WALL_CLOCK) {
            if(q.kind()==QuantityKind.DURATION_MINUTES)out.put("durationMinutes",mapper.valueToTree(q.value()));
            if(q.kind()==QuantityKind.ARRIVAL_LEAD_MINUTES)out.put("arrivalLeadMinutes",mapper.valueToTree(q.value()));
        }
        return out;
    }
    private Set<String> dependentFields(Booking booking,Set<String> corrected) {
        var changed=new HashSet<String>();
        for(var s:booking.schedules())if(switch(s.target()) {
            case START->corrected.contains("startDate") || corrected.contains("startTime");
            case END->corrected.contains("endDate") || corrected.contains("endTime");
            case REQUIRED_ARRIVAL->corrected.contains("requiredArrivalLocal");case CLAUSE_ACTION->false;
        })changed.add(s.id());
        var invalidated=new HashSet<String>();boolean again;
        do {again=false;
            for(var s:booking.schedules())if(!changed.contains(s.id()) && s.fact().value()!=null) {
                var v=s.fact().value();
                for(var m:Arrays.asList(v.point(),v.lower()==null?null:v.lower().moment(),v.upper()==null?null:v.upper().moment()))
                    if(m!=null && m.offset()!=null && (changed.contains(m.offset().schedule())
                            || corrected.contains("timezone") && m.offset().basis()==SemanticFactV3.TemporalBasis.ELAPSED)) {
                        changed.add(s.id());again=true;
                        switch(s.target()){case START->{invalidated.add("startDate");invalidated.add("startTime");}
                            case END->{invalidated.add("endDate");invalidated.add("endTime");}
                            case REQUIRED_ARRIVAL->invalidated.add("requiredArrivalLocal");case CLAUSE_ACTION->{}}
                    }
            }
        }while(again);
        for(var q:booking.quantities())if(q.fact().value()!=null && q.fact().value().difference()!=null) {
            var d=q.fact().value().difference();
            if(changed.contains(d.fromSchedule()) || changed.contains(d.toSchedule())
                    || corrected.contains("timezone") && d.basis()==SemanticFactV3.TemporalBasis.ELAPSED) {
                if(q.kind()==QuantityKind.DURATION_MINUTES)invalidated.add("durationMinutes");
                if(q.kind()==QuantityKind.ARRIVAL_LEAD_MINUTES)invalidated.add("arrivalLeadMinutes");
            }
        }
        return invalidated;
    }
    private JsonNode node(Fact<?> fact){return mapper.valueToTree(fact.status()==CLEAR?fact.value():null);}
    private JsonNode canonical(String field,JsonNode value) {
        if(field.equals("requiredArrivalLocal") && value.isTextual())try{return mapper.valueToTree(value.asText().isEmpty()?null:LocalDateTime.parse(value.asText()).toString());}catch(DateTimeException invalid){return value;}
        return value;
    }
    private void validateValue(String field,JsonNode value) {
        try {
            if(field.equals(NativeScheduleClarification.FIELD)) {NativeScheduleClarification.parse(value,mapper);}
            else if(field.equals("lifecycle")) {
                if(!value.isObject() || value.size()!=3 || !value.path("state").isTextual() || !value.path("reason").isTextual() || !value.path("acknowledgeSourceConflict").isBoolean())throw new IllegalArgumentException();
                var d=mapper.treeToValue(value,LifecycleClarification.class);
                if(d.state()==null || d.reason().isBlank() || d.reason().length()>500)throw new IllegalArgumentException();
            } else if(field.equals("meetingPoint") || field.equals("endingPoint")) {
                if(!value.isNull()) {
                    var l=mapper.readerFor(TourBookingFacts.DocumentLocation.class).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value.toString());
                    var location=(TourBookingFacts.DocumentLocation)l;
                    if((location.name()==null || location.name().isBlank()) && (location.address()==null || location.address().isBlank()) || location.name()!=null && location.name().length()>255 || location.address()!=null && location.address().length()>1000
                            || (location.latitude()==null)!=(location.longitude()==null) || location.latitude()!=null && (!Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude()) || Math.abs(location.latitude())>90 || Math.abs(location.longitude())>180))throw new IllegalArgumentException();
                }
            } else if(field.equals("durationMinutes") || field.equals("arrivalLeadMinutes")) {
                if(!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<(field.equals("durationMinutes")?1:0)))throw new IllegalArgumentException();
            } else {
                if(!value.isNull() && !value.isTextual())throw new IllegalArgumentException();if(value.isNull())return;
                if(field.endsWith("Date"))LocalDate.parse(value.asText());else if(field.endsWith("Time"))LocalTime.parse(value.asText());
                else if(field.equals("requiredArrivalLocal"))LocalDateTime.parse(value.asText());
                else if(field.equals("timezone") && !ZoneId.getAvailableZoneIds().contains(value.asText()))throw new IllegalArgumentException();
                else if(field.equals("endingLocationChoice") && !Set.of("UNKNOWN","SAME_AS_MEETING_POINT","LOCATION").contains(value.asText()))throw new IllegalArgumentException();
                else if(Set.of("name","provider","bookingReference").contains(field) && value.asText().length()>255)throw new IllegalArgumentException();
            }
        }catch(RuntimeException invalid){throw TourCorrectionException.validation("INVALID_CORRECTION_VALUE","Invalid accepted correction value.");}
    }
    private static void block(List<SourceBookingReview.Issue> issues,String code,String field){issues.add(new SourceBookingReview.Issue(code,field,true));}
    private void endpoint(Map<String,JsonNode> values,Set<String> corrected,List<SourceBookingReview.Issue> issues) {
        if(Collections.disjoint(corrected,Set.of("meetingPoint","endingPoint","endingLocationChoice")))return;
        issues.removeIf(i->i.field().equals("endingPoint") && (i.code().equals("INVALID_BOOKING_LOCATION") || i.code().equals("CONFLICTING_ENDPOINT_DECISIONS")));
        String choice=values.get("endingLocationChoice").asText();
        if(corrected.contains("endingPoint") && !corrected.contains("endingLocationChoice")) {
            choice=values.get("endingPoint").isNull()?"UNKNOWN":"LOCATION";values.put("endingLocationChoice",mapper.valueToTree(choice));
        }
        if(choice.equals("SAME_AS_MEETING_POINT")) {
            if(values.get("meetingPoint").isNull())block(issues,"INVALID_BOOKING_LOCATION","endingPoint");
            else if(corrected.contains("endingPoint") && !values.get("endingPoint").equals(values.get("meetingPoint")))block(issues,"CONFLICTING_ENDPOINT_DECISIONS","endingPoint");
            else values.put("endingPoint",values.get("meetingPoint").deepCopy());
        } else if(choice.equals("UNKNOWN")) {
            if(corrected.contains("endingPoint") && !values.get("endingPoint").isNull())block(issues,"CONFLICTING_ENDPOINT_DECISIONS","endingPoint");
            else values.put("endingPoint",mapper.nullNode());
        } else if(values.get("endingPoint").isNull())block(issues,"INVALID_BOOKING_LOCATION","endingPoint");
    }
}
