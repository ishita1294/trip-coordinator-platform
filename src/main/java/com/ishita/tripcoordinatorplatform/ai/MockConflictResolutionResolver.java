package com.ishita.tripcoordinatorplatform.ai;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Temporary stand-in for the Bedrock model while model access
 * is unavailable. Remove once the real Bedrock resolver is enabled.
 */
@Component
@ConditionalOnProperty(
        name = "app.ai.conflict-resolution.provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockConflictResolutionResolver implements ConflictResolutionResolver {

    @Override
    public String resolve(String prompt) {

        return """
                {
                  "options": [
                    {
                      "itemId": 1,
                      "proposedStartDateTime": "2026-12-10T08:00:00",
                      "proposedEndDateTime": "2026-12-10T09:00:00",
                      "reason": "Move breakfast earlier to resolve the overlap.",
                      "verificationRequired": false,
                      "verificationReason": null
                    }
                  ]
                }
                """;
    }


}