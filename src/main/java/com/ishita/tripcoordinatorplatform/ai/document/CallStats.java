package com.ishita.tripcoordinatorplatform.ai.document;

import com.openai.models.responses.Response;

/** Timing and token usage of one provider call. Null token counts mean the provider did not report them. */
public record CallStats(String model, long elapsedMs, String status, Long inputTokens, Long cachedInputTokens,
        Long outputTokens, Long reasoningTokens, String requestedTier, String actualTier) {

    public static CallStats of(String model, long elapsedMs, Response response, String requestedTier) {
        var usage = response.usage().orElse(null);
        return new CallStats(model, elapsedMs, response.status().map(Object::toString).orElse("MISSING"),
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage._inputTokensDetails().asKnown()
                        .flatMap(details -> details._cachedTokens().asKnown()).orElse(null),
                usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage._outputTokensDetails().asKnown()
                        .flatMap(details -> details._reasoningTokens().asKnown()).orElse(null),
                requestedTier, response.serviceTier().map(Response.ServiceTier::asString).orElse("UNKNOWN"));
    }

    /** Output that is not reasoning, or null when the split is unknown or inconsistent. */
    public Long nonReasoningOutputTokens() {
        return outputTokens != null && reasoningTokens != null && reasoningTokens >= 0 && reasoningTokens <= outputTokens
                ? outputTokens - reasoningTokens : null;
    }
}
