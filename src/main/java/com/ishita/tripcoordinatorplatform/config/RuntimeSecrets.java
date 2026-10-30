package com.ishita.tripcoordinatorplatform.config;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Loads runtime secrets into memory before application clients are created. */
public final class RuntimeSecrets {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RuntimeSecrets.class);
    private final SecretsManagerClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    RuntimeSecrets(SecretsManagerClient client) {
        this.client = client;
    }

    public static Map<String, Object> load(String rdsArn, String openaiArn, String region) {
        return load(rdsArn, openaiArn, region, null);
    }

    public static Map<String, Object> load(String rdsArn, String openaiArn, String region, String googleArn) {
        requireArn(rdsArn, "RDS_SECRET_ARN");
        requireArn(openaiArn, "OPENAI_SECRET_ARN");
        if (googleArn != null) requireArn(googleArn, "GOOGLE_MAPS_SECRET_ARN");
        SecretsManagerClient client;
        long clientStarted = System.nanoTime();
        try {
            var builder = SecretsManagerClient.builder()
                    .credentialsProvider(DefaultCredentialsProvider.builder().build());
            if (region != null) builder.region(software.amazon.awssdk.regions.Region.of(region));
            client = builder.build();
            LOG.info("Runtime secrets stage=client-init status=completed elapsedMs={}",
                    (System.nanoTime() - clientStarted) / 1_000_000);
        } catch (RuntimeException failure) {
            LOG.warn("Runtime secrets stage=client-init status=failed elapsedMs={}",
                    (System.nanoTime() - clientStarted) / 1_000_000);
            throw new IllegalStateException("Runtime Secrets Manager client could not be initialized; verify region and credential configuration");
        }
        try (client) {
            var loader = new RuntimeSecrets(client);
            var secrets = new java.util.HashMap<String, Object>(loader.load(rdsArn, openaiArn));
            if (googleArn != null) secrets.put("app.explore.google.api-key", loader.field(googleArn, "apiKey", "Google Maps"));
            return Map.copyOf(secrets);
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
        long started = System.nanoTime();
        boolean completed = false;
        try {
            String json;
            try {
                json = client.getSecretValue(GetSecretValueRequest.builder().secretId(arn).build()).secretString();
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Failed to read " + label + " runtime secret; verify credentials, expiration, secret identifier and permissions");
            }
            try {
                if (json != null) {
                    var object = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
                    var value = object != null && object.isObject() ? object.get(field) : null;
                    if (value != null && value.isTextual() && !value.asText().isBlank()) {
                        completed = true;
                        return value.asText();
                    }
                }
            } catch (RuntimeException failure) {
                throw new IllegalStateException(label + " runtime secret must contain valid JSON");
            }
            throw new IllegalStateException(label + " runtime secret is missing required field: " + field);
        } finally {
            // Log the secret's purpose and duration, never its ARN, value or provider error body.
            LOG.info("Runtime secrets stage=secret-load secret={} status={} elapsedMs={}",
                    label, completed ? "completed" : "failed", (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static String requireArn(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for runtime secret loading");
        return value;
    }
}
