package com.ishita.tripcoordinatorplatform.ai.document;

import com.ishita.tripcoordinatorplatform.model.Location;
import java.time.LocalDateTime;
import java.util.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticFact.Status.*;

/** Explicit boundary into existing booking fields. Full source facts remain in the immutable envelope. */
public record SemanticBookingProposal(AttractionTourBookingExtraction booking, LocalDateTime requiredArrivalLocal,
                                      String endingLocationChoice, List<String> unresolvedFields) {
    public static SemanticBookingProposal fromV3(SemanticTourExtractionV3.Booking b,
            Map<SemanticFactV3.Reference,SemanticTourExtractionV3Validator.Verification> checks) {
        var view=from(authoritativeV3(b,checks));
        // Optional identity details may remain unknown, but scheduling/location contradictions still need review.
        var issues=new ArrayList<>(view.unresolvedFields().stream().filter(f->!Set.of("provider","bookingReference").contains(f)).toList());
        if(b.requiredArrival().status()==CLEAR && !usable(b.requiredArrival(),b.bookingIndex(),SemanticFact.Field.REQUIRED_ARRIVAL,checks)
                && view.booking().arrivalLeadMinutes()==null)issues.add("requiredArrivalLocal");
        if (!SemanticTemporalConstraintV3.representable(b)) issues.add("temporalConstraints");
        return new SemanticBookingProposal(view.booking(),view.requiredArrivalLocal(),view.endingLocationChoice(),List.copyOf(issues));
    }
    public static SemanticTourExtractionValidator.Timing timingV3(SemanticTourExtractionV3.Booking b,
            Map<SemanticFactV3.Reference,SemanticTourExtractionV3Validator.Verification> checks) {
        if (!SemanticTemporalConstraintV3.representable(b))
            throw new InvalidDocumentExtractionException("Temporal constraint cannot be flattened into scheduling fields");
        return new SemanticTourExtractionValidator().timing(authoritativeV3(b,checks));
    }
    private static SemanticTourExtraction.Booking authoritativeV3(SemanticTourExtractionV3.Booking b,
            Map<SemanticFactV3.Reference,SemanticTourExtractionV3Validator.Verification> checks) {
        return new SemanticTourExtraction.Booking(b.bookingIndex(),safe(b.name(),b,SemanticFact.Field.NAME,checks),
                safe(b.provider(),b,SemanticFact.Field.PROVIDER,checks),safe(b.bookingReference(),b,SemanticFact.Field.BOOKING_REFERENCE,checks),
                safe(b.start(),b,SemanticFact.Field.START,checks),safe(b.end(),b,SemanticFact.Field.END,checks),safe(b.timezone(),b,SemanticFact.Field.TIMEZONE,checks),
                safe(b.durationMinutes(),b,SemanticFact.Field.DURATION_MINUTES,checks),safe(b.requiredArrival(),b,SemanticFact.Field.REQUIRED_ARRIVAL,checks),
                safe(b.arrivalLeadMinutes(),b,SemanticFact.Field.ARRIVAL_LEAD_MINUTES,checks),safe(b.meetingPoint(),b,SemanticFact.Field.MEETING_POINT,checks),
                safe(b.endingPoint(),b,SemanticFact.Field.ENDING_POINT,checks),safe(b.instructions(),b,SemanticFact.Field.INSTRUCTIONS,checks),List.of());
    }
    private static boolean usable(SemanticFactV3<?> f,int index,SemanticFact.Field field,
            Map<SemanticFactV3.Reference,SemanticTourExtractionV3Validator.Verification> checks) {
        return f.interpretation().basis()!=SemanticFact.Basis.CALCULATED
                || checks.get(new SemanticFactV3.Reference(index,field,null))==SemanticTourExtractionV3Validator.Verification.ELAPSED_ARITHMETIC_VERIFIED;
    }
    private static <T> SemanticFact<T> safe(SemanticFactV3<T> f,SemanticTourExtractionV3.Booking b,SemanticFact.Field field,
            Map<SemanticFactV3.Reference,SemanticTourExtractionV3Validator.Verification> checks) {
        return usable(f,b.bookingIndex(),field,checks)?f.shape():new SemanticFact<>(SemanticFact.Status.MISSING,null,List.of(),List.of(),
                "Provisional calculation excluded from authoritative mapping",new SemanticFact.Derivation(SemanticFact.Basis.NOT_ESTABLISHED,List.of()));
    }
    public static SemanticBookingProposal from(SemanticTourExtraction.Booking b) {
        var start=clear(b.start());var end=clear(b.end());var ending=clear(b.endingPoint());
        String choice=ending==null?"UNKNOWN":switch(ending.meaning()) {
            case SAME_AS_MEETING_POINT -> "SAME_AS_MEETING_POINT";
            case DIFFERENT_LOCATION -> "LOCATION";case UNKNOWN -> "UNKNOWN";
        };
        var issues=new ArrayList<String>();
        if(clear(b.name())==null)issues.add("name");
        if(start==null)issues.add("startDate/startTime");
        if(clear(b.timezone())==null)issues.add("timezone");
        Map<String,SemanticFact<?>> material=new LinkedHashMap<>();
        material.put("provider",b.provider());material.put("bookingReference",b.bookingReference());
        material.put("endDate/endTime",b.end());material.put("durationMinutes",b.durationMinutes());
        material.put("requiredArrivalLocal",b.requiredArrival());material.put("arrivalLeadMinutes",b.arrivalLeadMinutes());
        material.put("meetingPoint",b.meetingPoint());
        // An uncertain endpoint may remain Unknown; conflicting explicit endpoints need source review.
        if(b.endingPoint().status()==CONFLICTING)issues.add("endingPoint/endingLocationChoice");
        material.forEach((key,f)->{if(f.status()==AMBIGUOUS || f.status()==CONFLICTING)issues.add(key);});
        return new SemanticBookingProposal(new AttractionTourBookingExtraction(clear(b.name()),clear(b.bookingReference()),clear(b.provider()),
                start==null?null:start.date(),start==null?null:start.time(),end==null?null:end.date(),end==null?null:end.time(),
                clear(b.durationMinutes()),location(clear(b.meetingPoint())),clear(b.timezone()),instructions(b),clear(b.arrivalLeadMinutes()),
                ending==null?null:location(ending.location())),
                clear(b.requiredArrival())==null?null:clear(b.requiredArrival()).toLocalDateTime(),choice,List.copyOf(issues));
    }
    private static String instructions(SemanticTourExtraction.Booking b) {
        var meeting=clear(b.meetingPoint());
        return java.util.stream.Stream.of(clear(b.instructions()),meeting==null?null:meeting.instructions())
                .filter(Objects::nonNull).distinct().collect(java.util.stream.Collectors.joining("\n"));
    }
    private static Location location(SemanticTourExtraction.DocumentLocation source) {
        if(source==null)return null;
        var result=new Location();
        result.setName(java.util.stream.Stream.of(source.name(),source.entrance()).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(", ")));
        result.setAddress(source.address()); // Printed coordinates are documentary evidence, not provider observations.
        return result;
    }
    private static <T>T clear(SemanticFact<T> f){return f.status()==CLEAR?f.value():null;}
}
