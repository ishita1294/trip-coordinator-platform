package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.util.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SourceBookingExtraction.*;

/** User-declared overlay only. Never rewrites source claims or invokes a provider. */
public final class NativeScheduleClarification {
    private NativeScheduleClarification() {}
    public static final String FIELD="nativeScheduleBindings";
    public record Claim(String scheduleId,ScheduleBinding binding) {}
    public record Proposal(List<Claim> claims,String primaryStart,String primaryEnd,boolean deriveElapsedDuration,
            String reason,boolean acknowledgeSource) {}
    static Proposal parse(JsonNode value,JsonMapper mapper) {
        try {
            var p=mapper.rebuild().disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build()
                    .readerFor(Proposal.class).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).<Proposal>readValue(value.toString());
            if(p.claims()==null || p.claims().isEmpty() || p.claims().size()>32 || p.reason()==null || p.reason().isBlank()
                    || p.reason().length()>500 || !p.acknowledgeSource() || p.primaryStart()==null || p.primaryEnd()==null)throw new IllegalArgumentException();
            return p;
        }catch(RuntimeException invalid){throw TourCorrectionException.invalid("Explicit schedule bindings, source acknowledgement and reason are required.");}
    }
    static SourceBookingReviewV1.Review apply(SourceBookingReviewV1.Review base,int index,JsonNode value,
            JsonMapper mapper,DocumentAiInput source) {
        var p=parse(value,mapper);var row=base.bookings().get(index);var bindings=new HashMap<String,ScheduleBinding>();
        for(var claim:p.claims())if(claim==null || claim.scheduleId()==null || claim.binding()==null
                || claim.binding().timezone()==null || claim.binding().timezone().status()!=SemanticFact.Status.CLEAR
                || claim.binding().role()==null || claim.binding().role().status()!=SemanticFact.Status.CLEAR
                || bindings.putIfAbsent(claim.scheduleId(),claim.binding())!=null)
            throw TourCorrectionException.invalid("Supply one explicit timezone and role for each existing schedule claim.");
        if(!bindings.keySet().equals(new HashSet<>(row.original().schedules().stream().map(ScheduleClaim::id).toList())))
            throw TourCorrectionException.invalid("Clarification must bind the exact existing schedule claims; no source values may be added or removed.");
        var tree=(ObjectNode)mapper.valueToTree(new SourceBookingExtraction(SCHEMA_VERSION,CONTRACT,base.sources(),
                base.bookings().stream().map(SourceBookingReviewV1.BookingReview::original).toList(),
                base.clauses().stream().map(SourceBookingReviewV1.ClauseView::original).toList(),base.coverageWarning()));
        var booking=(ObjectNode)tree.path("bookings").get(index);
        for(var claim:booking.path("schedules"))((ObjectNode)claim).set("binding",mapper.valueToTree(bindings.get(claim.path("id").asText())));
        var projections=(ArrayNode)booking.path("projections");
        for(int i=projections.size()-1;i>=0;i--)if(Set.of("START","END").contains(projections.get(i).path("field").asText()))projections.remove(i);
        projections.addObject().put("field","START").put("schedule",p.primaryStart()).put("edge","POINT");
        projections.addObject().put("field","END").put("schedule",p.primaryEnd()).put("edge","POINT");
        if(p.deriveElapsedDuration())for(var node:booking.path("quantities"))if(node.path("kind").asText().equals("DURATION_MINUTES") && node.path("clauseIndex").isNull()) {
            var fact=node.path("fact");
            if(fact.path("status").asText().equals("MISSING")) {
                var out=(ObjectNode)fact;out.put("status","CLEAR");out.putArray("alternatives");out.putArray("sources");out.putNull("uncertainty");
                var number=out.putObject("value");number.putNull("literal");number.putObject("difference").put("fromSchedule",p.primaryStart()).put("fromEdge","POINT")
                        .put("toSchedule",p.primaryEnd()).put("toEdge","POINT").put("basis","ELAPSED");
            } else if(!fact.path("status").asText().equals("CLEAR"))throw TourCorrectionException.invalid("An unresolved source duration cannot be replaced by a derived value.");
        }
        try {
            var effective=SourceBookingReviewV1.read(tree.toString(),mapper,source);var resolved=effective.bookings().get(index).deterministic();
            if(resolved.projections().stream().filter(x->x.field()==Target.START || x.field()==Target.END)
                    .filter(x->x.temporalResolution()==SourceBookingReader.TemporalResolution.UNIQUE_INSTANT).count()!=2)
                throw new IllegalArgumentException();
            var start=resolved.projections().stream().filter(x->x.field()==Target.START).findFirst().orElseThrow();
            var end=resolved.projections().stream().filter(x->x.field()==Target.END).findFirst().orElseThrow();
            if(!end.instant().isAfter(start.instant()))throw new IllegalArgumentException();
            return effective;
        }catch(IllegalArgumentException invalid){
            throw TourCorrectionException.validation("INVALID_SCHEDULE_CLARIFICATION","Bindings do not establish an unambiguous source-consistent interval; check roles, IANA zones, evidence, conflicts and DST.");
        }
    }
    static TourSourceAssessment assessment(TourSourceAssessment value,List<com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.Change> changes) {
        if(changes.stream().noneMatch(c->FIELD.equals(c.field())))return TourCorrectionService.normalize(value,changes);
        if(changes.size()!=1 || !FIELD.equals(changes.getFirst().field()) || value==null
                || !"USER_SCHEDULE_DECLARATION".equals(value.model()) || value.acceptedStateToken()==null
                || value.findings()==null || value.findings().size()!=1)throw TourCorrectionException.invalid("Invalid native schedule assessment.");
        var f=value.findings().getFirst();var c=changes.getFirst();
        if(f.bookingIndex()!=c.bookingIndex() || !FIELD.equals(f.field()) || f.outcome()!=TourSourceAssessment.Outcome.USER_REPORTED_SCHEDULE_CLARIFICATION)
            throw TourCorrectionException.invalid("Schedule clarification provenance mismatch.");
        return value;
    }
}
