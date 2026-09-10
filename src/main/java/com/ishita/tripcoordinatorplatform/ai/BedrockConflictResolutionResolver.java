package com.ishita.tripcoordinatorplatform.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.ai.conflict-resolution.provider",
        havingValue = "bedrock"
)
public class BedrockConflictResolutionResolver
        implements ConflictResolutionResolver {

    @Override
    public String resolve(String prompt) {
        return "";
    }
}