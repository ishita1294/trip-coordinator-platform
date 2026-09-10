package com.ishita.tripcoordinatorplatform.ai;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Sends the same evaluation prompt to different Bedrock models
 * so their conflict-resolution responses can be compared.
 */
@Service
public class BedrockModelEvaluator {

    private final BedrockRuntimeClient bedrockClient;

    public BedrockModelEvaluator(BedrockRuntimeClient bedrockClient) {
        this.bedrockClient = bedrockClient;
    }

    /**
     * Sends one prompt to the specified Bedrock model
     * and returns the model's raw text response.
     */
    public String evaluate(String modelId, String prompt) {

        // Wrap the prompt as a user message for Bedrock Converse.
        Message message = Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(prompt))
                .build();

        // Send the same prompt and inference settings to the chosen model.
        ConverseResponse response = bedrockClient.converse(request -> request
                .modelId(modelId)
                .messages(message)
                .inferenceConfig(config -> config
                        .maxTokens(1200)
                        .temperature(0.2F)));

        // Extract the model's text response.
        return response.output()
                .message()
                .content()
                .get(0)
                .text();
    }


}
