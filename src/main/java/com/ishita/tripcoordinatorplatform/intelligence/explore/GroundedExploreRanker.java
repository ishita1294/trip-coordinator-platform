package com.ishita.tripcoordinatorplatform.intelligence.explore;

import org.springframework.context.annotation.Profile;
import com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Profile("!document-worker")
@Service
public class GroundedExploreRanker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GroundedExploreRanker.class);
    public record Ranking(List<VisitFeasibility> ordered, String status) {}
    private final String apiKey, model;
    private final com.openai.client.OpenAIClient client;
    private final boolean enabled;
    private final JsonMapper mapper;
    public GroundedExploreRanker(@Value("${app.ai.openai.api-key:}") String apiKey,
            @Value("${app.ai.openai.explore-model:${app.ai.openai.extraction-model}}") String model,
            @Value("${app.explore.ai.enabled:true}") boolean enabled, JsonMapper mapper) {
        this.apiKey = apiKey; this.model = model; this.enabled = enabled; this.mapper = mapper;
        this.client = enabled && apiKey != null && !apiKey.isBlank()
                ? OpenAIOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(10)).maxRetries(0).build() : null;
    }

    public Ranking rank(List<VisitFeasibility> candidates, ExplorePreference preference) {
        long preparationStarted = System.nanoTime();
        var selected = CandidateSelection.orderBounded(candidates,0);
        if (selected.isEmpty()) return new Ranking(selected, "NO_CANDIDATES");
        if (!enabled || apiKey == null || apiKey.isBlank()) return new Ranking(selected.stream().map(c -> c.explained(evidence(c))).toList(), "AI_UNAVAILABLE_DETERMINISTIC_ORDER");
        // Only identifiers and grounded derived facts; no user location, participant names or booking data.
        var facts = selected.stream().map(c -> {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("id", c.place().id()); fact.put("status", c.status());
            fact.put("availableMinutes", c.availableVisitMinutes()); fact.put("types", c.place().types());
            fact.put("outboundMinutes", c.outbound() == null ? null : Duration.between(c.outbound().departureAt(), c.outbound().arrivalAt()).toMinutes());
            fact.put("onwardMinutes", c.returnPlan() == null ? null : Duration.between(c.returnPlan().route().departureAt(), c.returnPlan().route().arrivalAt()).toMinutes());
            fact.put("visitStartsAt", c.visitStartsAt()); fact.put("visitEndsAt", c.visitEndsAt());
            fact.put("requiredArrivalAt", c.returnPlan() == null ? null : c.returnPlan().requiredArrivalAt());
            fact.put("openingHoursKnown", c.place().hours() != null);
            fact.put("verifiedDurationMinutes", c.place().verifiedDuration() == null ? null : c.place().verifiedDuration().toMinutes());
            fact.put("reasonCodes", c.reasons().stream().map(r -> r.code()).toList());
            fact.put("rating", c.place().rating()); fact.put("reviewCount", c.place().reviewCount());
            fact.put("discoveryOrder", selected.indexOf(c));
            fact.put("experienceFamily", com.ishita.tripcoordinatorplatform.intelligence.places.ActivityRelevance.family(c.place()));
            fact.put("practicalScore", CandidateSelection.boundedScore(c));
            fact.put("travelMode", c.outbound()==null ? null : c.outbound().mode());
            fact.put("allowedReasons", allowedReasons(c, preference)); return fact;
        }).toList();
        try {
            var schema = Map.of("type", "object", "properties", Map.of("rankings", Map.of("type", "array", "items",
                    Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"), "reason",
                            Map.of("type", "string", "enum", List.of("TIME_WINDOW", "PREFERENCE_MATCH", "VERIFY_CONSTRAINTS"))),
                            "required", List.of("id", "reason"), "additionalProperties", false))),
                    "required", List.of("rankings"), "additionalProperties", false);
            String factsJson = mapper.writeValueAsString(facts);
            LOG.info("EXPLORE AI preparation: candidates={}, factsBytes={}, elapsedMs={}", selected.size(),
                    factsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, (System.nanoTime()-preparationStarted)/1_000_000);
            var params = ResponseCreateParams.builder().model(model).maxOutputTokens(600L)
                    .instructions("Rank these candidate IDs using only the supplied facts and preference. Balance reputation evidence, category diversity, discovery order and schedule practicality; do not sort solely by available time. Prefer lower total travel effort among similarly relevant experiences, especially walking. Treat worship sites as one experience family. Ratings are confidence evidence, not universal visitor value. Return each ID once. "
                            + "Choose exactly one of that candidate's allowedReasons. Do not infer durations, routes, admission, hours or locations. "
                            + "Do not promote conditional status to feasible. The backend renders the selected grounded explanation.")
                    .input("Preference: " + preference + "\nFacts: " + factsJson)
                    .text(ResponseTextConfig.builder().format(ResponseFormatTextJsonSchemaConfig.builder().name("explore_ranking")
                            .strict(true).schema(JsonValue.from(schema).convert(ResponseFormatTextJsonSchemaConfig.Schema.class)).build()).build()).build();
            String raw = invoke(params).output().stream().flatMap(i -> i.message().stream())
                    .flatMap(m -> m.content().stream()).flatMap(c -> c.outputText().stream()).map(t -> t.text()).collect(Collectors.joining());
            var ranked = mapper.readTree(raw).path("rankings");
            if (!ranked.isArray() || ranked.size() != selected.size()) throw new IllegalArgumentException("Invalid ranking");
            Map<String, VisitFeasibility> known = new HashMap<>(); selected.forEach(c -> known.put(c.place().id(), c));
            Set<String> seen = new HashSet<>(); List<VisitFeasibility> result = new ArrayList<>();
            for (var item : ranked) {
                var candidate = known.get(item.path("id").asText()); String reason = item.path("reason").asText();
                if (candidate == null || !seen.add(candidate.place().id()) || !allowedReasons(candidate, preference).contains(reason)) {
                    throw new IllegalArgumentException("Ungrounded ranking");
                }
                // The selected reason remains schema-validated; facts and warnings stay structured.
                result.add(candidate.explained(evidence(candidate)));
            }
            return new Ranking(CandidateSelection.orderBounded(result,.2), "GROUNDED_AI_RANKING");
        } catch (RuntimeException exception) {
            return new Ranking(selected.stream().map(c -> c.explained(evidence(c))).toList(), "AI_UNAVAILABLE_DETERMINISTIC_ORDER");
        }
    }
    public record OpenRanking(List<OpenEndedAssessment> ordered, String status) {}
    public OpenRanking rankOpenEnded(List<OpenEndedAssessment> candidates, ExplorePreference preference) {
        long preparationStarted = System.nanoTime();
        var selected = CandidateSelection.orderOpenEnded(candidates);
        if (selected.isEmpty()) return new OpenRanking(selected, "NO_CANDIDATES");
        List<OpenEndedAssessment> explained = selected.stream().map(c -> c.explained(
                evidence(c.place()))).toList();
        if (client == null) return new OpenRanking(explainOpenOrder(explained), "AI_UNAVAILABLE_DETERMINISTIC_ORDER");
        if (!CandidateSelection.openOrderNeedsAi(selected))
            return new OpenRanking(explainOpenOrder(explained), "DETERMINISTIC_EVIDENCE_ORDER");
        try {
            var facts = selected.stream().map(c -> {
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("id", c.place().id()); fact.put("types", c.place().types());
                fact.put("rating", c.place().rating()); fact.put("reviewCount", c.place().reviewCount());
                fact.put("suitability", c.suitability()); fact.put("activityInterests", c.place().interests()); fact.put("evidenceTier", CandidateSelection.evidenceTier(c));
                fact.put("arrivalAt", c.outbound() == null ? null : c.outbound().arrivalAt()); fact.put("destinationTimeZone", c.place().timeZone()); fact.put("openingHoursEvidence", c.openingHoursEvidence());
                fact.put("outboundMinutes", c.outbound() == null ? null : Duration.between(c.outbound().departureAt(), c.outbound().arrivalAt()).toMinutes());
                fact.put("experienceFamily",com.ishita.tripcoordinatorplatform.intelligence.places.ActivityRelevance.family(c.place()));
                fact.put("practicalScore",CandidateSelection.openScore(c)); fact.put("travelMode",c.outbound()==null ? null : c.outbound().mode());
                fact.put("reasonCodes", c.reasons().stream().map(r -> r.code()).toList()); fact.put("discoveryOrder", selected.indexOf(c)); return fact;
            }).toList();
            var schema = Map.of("type", "object", "properties", Map.of("ids", Map.of("type", "array", "items", Map.of("type", "string"))), "required", List.of("ids"), "additionalProperties", false);
            String factsJson = mapper.writeValueAsString(facts);
            LOG.info("EXPLORE AI preparation: candidates={}, factsBytes={}, elapsedMs={}", selected.size(),
                    factsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, (System.nanoTime()-preparationStarted)/1_000_000);
            var params = ResponseCreateParams.builder().model(model).maxOutputTokens(600L)
                    .reasoning("gpt-6-sol".equals(model) ? Reasoning.builder().effort(ReasoningEffort.NONE).build() : null)
                    .instructions("Rank every supplied place ID once for open-ended discovery. Balance the supplied preference, reputation, diversity and outbound travel evidence. Prioritize known-open, route-supported activities over unknown-hours and late-night uncertainty. Category relevance is required; reputation or a route alone is not a reason to visit. There is NO schedule deadline, onward route or available visiting window. Never infer schedule fit, duration, admission or missing facts. Places of worship represent one spiritual/heritage experience family, not independent diversity. For similarly attractive options, prefer lower total travel effort, especially walking. High review counts do not prove a worthwhile experience. Preserve the practical score advantages; choose qualitative tie-breaks within similarly practical options. Return IDs only; the backend renders grounded evidence.")
                    .input("Preference: " + preference + "\nFacts: " + factsJson)
                    .text(ResponseTextConfig.builder().format(ResponseFormatTextJsonSchemaConfig.builder().name("open_discovery_ranking").strict(true)
                            .schema(JsonValue.from(schema).convert(ResponseFormatTextJsonSchemaConfig.Schema.class)).build()).build()).build();
            String raw = invoke(params).output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
                    .flatMap(c -> c.outputText().stream()).map(t -> t.text()).collect(Collectors.joining());
            var ids = mapper.readTree(raw).path("ids");
            if (!ids.isArray() || ids.size() != explained.size()) throw new IllegalArgumentException("Invalid ranking");
            Map<String, OpenEndedAssessment> known = new HashMap<>(); explained.forEach(c -> known.put(c.place().id(), c));
            List<OpenEndedAssessment> ordered = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (var id : ids) {
                if (!known.containsKey(id.asText()) || !seen.add(id.asText())) throw new IllegalArgumentException("Ungrounded ranking");
                ordered.add(known.get(id.asText()));
            }
            return new OpenRanking(explainOpenOrder(CandidateSelection.orderOpenEnded(ordered,.2)), "GROUNDED_AI_RANKING");
        } catch (RuntimeException failure) { return new OpenRanking(explainOpenOrder(explained), "AI_UNAVAILABLE_DETERMINISTIC_ORDER"); }
    }
    private Response invoke(ResponseCreateParams params) {
        long started = System.nanoTime();
        try {
            var response = client.responses().create(params);
            var usage = response.usage();
            LOG.info("EXPLORE AI SDK call completed: model={}, elapsedMs={}, inputTokens={}, outputTokens={}",
                    model, (System.nanoTime()-started)/1_000_000,
                    usage.map(ResponseUsage::inputTokens).orElse(null), usage.map(ResponseUsage::outputTokens).orElse(null));
            return response;
        } catch (RuntimeException failure) {
            LOG.warn("EXPLORE AI SDK call failed: model={}, elapsedMs={}", model, (System.nanoTime()-started)/1_000_000);
            throw failure;
        }
    }
    private List<OpenEndedAssessment> explainOpenOrder(List<OpenEndedAssessment> values) {
        return values; // Order is unchanged; travel and comparison facts have their own presentation.
    }
    private String evidence(com.ishita.tripcoordinatorplatform.intelligence.places.PlaceCandidate place) {
        return com.ishita.tripcoordinatorplatform.intelligence.places.ActivityRelevance.evidence(place);
    }
    private String evidence(VisitFeasibility candidate) {
        return evidence(candidate.place());
    }
    private List<String> allowedReasons(VisitFeasibility candidate, ExplorePreference preference) {
        List<String> reasons = new ArrayList<>();
        if (candidate.availableVisitMinutes() != null) reasons.add("TIME_WINDOW");
        // Discovery already filtered these place types by the requested preference.
        if (preference != ExplorePreference.ANYTHING) reasons.add("PREFERENCE_MATCH");
        if (reasons.isEmpty()) reasons.add("VERIFY_CONSTRAINTS");
        if (candidate.status() == VisitFeasibility.Status.CONDITIONAL) reasons.add("VERIFY_CONSTRAINTS");
        return reasons;
    }
}
