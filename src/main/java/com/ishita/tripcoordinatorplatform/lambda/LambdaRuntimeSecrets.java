package com.ishita.tripcoordinatorplatform.lambda;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Loads credentials only on the Lambda handler's cold-start path, before Spring starts. */
final class LambdaRuntimeSecrets {
    private final SecretsManagerClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    LambdaRuntimeSecrets(SecretsManagerClient client) {
        this.client = client;
    }

    static Map<String, Object> loadFromEnvironment() {
        String rdsArn = requireArn(System.getenv("RDS_SECRET_ARN"), "RDS_SECRET_ARN");
        String openaiArn = requireArn(System.getenv("OPENAI_SECRET_ARN"), "OPENAI_SECRET_ARN");
        // Region comes from the SDK default region chain (AWS_REGION in Lambda).
        SecretsManagerClient client;
        try {
            client = SecretsManagerClient.builder()
                    .credentialsProvider(DefaultCredentialsProvider.builder().build()).build();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Lambda Secrets Manager client could not be initialized");
        }
        try (client) {
            return new LambdaRuntimeSecrets(client).load(rdsArn, openaiArn);
        }
    }

    Map<String, Object> load(String rdsArn, String openaiArn) {
        requireArn(rdsArn, "RDS_SECRET_ARN");
        requireArn(openaiArn, "OPENAI_SECRET_ARN");
        return Map.of(
                "spring.datasource.password", field(rdsArn, "password", "RDS"),
                "app.ai.openai.api-key", field(openaiArn, "apiKey", "OpenAI"));
    }

    private String field(String arn, String field, String label) {
        String json;
        try {
            json = client.getSecretValue(GetSecretValueRequest.builder().secretId(arn).build()).secretString();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Failed to read " + label + " runtime secret");
        }
        try {
            if (json != null) {
                var object = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
                var value = object != null && object.isObject() ? object.get(field) : null;
                if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException(label + " runtime secret must contain valid JSON");
        }
        throw new IllegalStateException(label + " runtime secret is missing required field: " + field);
    }

    private static String requireArn(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for Lambda startup");
        return value;
    }
}
