package com.ishita.tripcoordinatorplatform.ai;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.ishita.tripcoordinatorplatform.model.ItineraryItemFlexibility;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;

/** No provider call: source-grounded alternatives, subsequently checked against the whole member schedule. */
@Component
@ConditionalOnProperty(name="app.ai.conflict-resolution.provider", havingValue="mock", matchIfMissing=true)
public class MockConflictResolutionResolver implements ConflictResolutionResolver {
    private final ObjectMapper mapper;
    public MockConflictResolutionResolver(ObjectMapper mapper) { this.mapper=mapper; }
    @Override public String resolve(String prompt) {
        throw new IllegalArgumentException("Verified conflict context is required for local suggestions");
    }
    @Override public String resolve(ConflictResolutionContext context, String prompt) {
        var options=new ArrayList<ConflictResolutionOption>();
        propose(context.getFirstItem(),context.getSecondItem(),options);
        propose(context.getSecondItem(),context.getFirstItem(),options);
        var response=new ConflictResolutionResponse(); response.setOptions(options);
        return mapper.writeValueAsString(response);
    }
    private void propose(ConflictResolutionItemContext item, ConflictResolutionItemContext other,
                         ArrayList<ConflictResolutionOption> options) {
        // No modeled rescheduling bounds exist for TIME_CONSTRAINED items. Do not guess them.
        if (item.getFlexibility()!=ItineraryItemFlexibility.FLEXIBLE || item.getStartDateTime()==null
                || other.getStartDateTime()==null) return;
        Duration duration=item.getEndDateTime()==null ? null : Duration.between(item.getStartDateTime(),item.getEndDateTime());
        if (duration!=null && (duration.isZero() || duration.isNegative())) return;
        if (other.getEndDateTime()!=null) add(item,other.getEndDateTime(),duration,options,"Move after the overlapping item, keeping the recorded duration.");
        if (duration!=null) add(item,other.getStartDateTime().minus(duration),duration,options,"Move before the overlapping item, keeping the recorded duration.");
    }
    private void add(ConflictResolutionItemContext item,LocalDateTime start,Duration duration,
                     ArrayList<ConflictResolutionOption> options,String reason) {
        var option=new ConflictResolutionOption();option.setItemId(item.getItemId());option.setProposedStartDateTime(start);
        option.setProposedEndDateTime(duration==null ? null : start.plus(duration));
        option.setReason(duration==null ? "Move after the overlapping item; the end time remains unknown." : reason);
        option.setVerificationRequired(true);option.setVerificationReason("Check venue availability and travel time before applying this schedule change.");
        options.add(option);
    }
}
