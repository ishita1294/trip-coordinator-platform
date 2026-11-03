package com.ishita.tripcoordinatorplatform.lambda;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentProcessingBudget;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ishita.tripcoordinatorplatform.TripCoordinatorPlatformApplication;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingWorkerClaimService;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingException;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingFailureService;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingWorkerExecutionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Thin SQS adapter; processing and ownership remain in the Spring services. */
public class DocumentProcessingLambdaHandler implements RequestHandler<SQSEvent, Void> {
    private final DocumentProcessingWorkerClaimService claims;
    private final DocumentProcessingWorkerExecutionService worker;
    private final JsonMapper mapper;
    private final int maxDeliveries;
    private DocumentProcessingVisibility visibility;

    public DocumentProcessingLambdaHandler() {
        // Keep on-demand Init lazy; only explicitly opted-in provisioned environments bootstrap here.
        this(null, null, null, 5);
        bootstrapIfProvisioned(System.getenv("AWS_LAMBDA_INITIALIZATION_TYPE"),
                System.getenv("DOCUMENT_WORKER_PROVISIONED_BOOTSTRAP"), () -> {
                    var application = SpringContextHolder.CONTEXT;
                    if (!application.isActive()) throw new IllegalStateException("Document worker context is not active");
                });
    }

    static void bootstrapIfProvisioned(String initializationType, String enabled, Runnable bootstrap) {
        if ("provisioned-concurrency".equals(initializationType) && "true".equalsIgnoreCase(enabled)) {
            // No event, budget, receipt, claim or inference path is entered. Fail Init if startup fails.
            bootstrap.run();
        }
    }

    // Tests inject services without starting Spring or connecting to infrastructure.
    DocumentProcessingLambdaHandler(DocumentProcessingWorkerClaimService claims,
                                    DocumentProcessingWorkerExecutionService worker, JsonMapper mapper) {
        this(claims, worker, mapper, 5);
    }

    DocumentProcessingLambdaHandler(DocumentProcessingWorkerClaimService claims,
                                    DocumentProcessingWorkerExecutionService worker, JsonMapper mapper, int maxDeliveries) {
        this(claims, worker, mapper, maxDeliveries, null);
    }

    DocumentProcessingLambdaHandler(DocumentProcessingWorkerClaimService claims,
            DocumentProcessingWorkerExecutionService worker, JsonMapper mapper, int maxDeliveries,
            DocumentProcessingVisibility visibility) {
        if (maxDeliveries < 1 || maxDeliveries > 5) throw new IllegalArgumentException("Maximum deliveries must remain within five");
        this.visibility = visibility;
        this.maxDeliveries = maxDeliveries;
        this.claims = claims;
        this.worker = worker;
        this.mapper = mapper;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        try (var _ = DocumentProcessingBudget.forInvocation(
                context == null ? null : context::getRemainingTimeInMillis)) {
            if (claims == null && visibility == null) visibility = DocumentProcessingVisibility.fromEnvironment();
            if (visibility != null) {
                if (event == null || event.getRecords() == null || event.getRecords().isEmpty())
                    throw new IllegalArgumentException("SQS records are required");
                // Protect startup before loading Spring or secrets. No provider work precedes this call.
                for (var record : event.getRecords()) visibility.protectStartup(record,
                        context == null ? DocumentProcessingBudget.LAMBDA_SECONDS * 1000 : context.getRemainingTimeInMillis());
            }
            return processRequest(event, context);
        }
    }

    private Void processRequest(SQSEvent event, Context context) {
        if (claims == null) {
            long started = System.nanoTime();
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Document worker context status=starting remainingMs={}",
                    context == null ? null : context.getRemainingTimeInMillis());
            ConfigurableApplicationContext application;
            try {
                application = SpringContextHolder.CONTEXT;
            } catch (RuntimeException | LinkageError failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "Document worker context status=failed elapsedMs={} causeClass={}",
                        (System.nanoTime() - started) / 1_000_000, failure.getClass().getSimpleName());
                throw failure;
            }
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Document worker context ready elapsedMs={} remainingMs={}",
                    (System.nanoTime() - started) / 1_000_000,
                    context == null ? null : context.getRemainingTimeInMillis());
            return new DocumentProcessingLambdaHandler(application.getBean(DocumentProcessingWorkerClaimService.class),
                    application.getBean(DocumentProcessingWorkerExecutionService.class), application.getBean(JsonMapper.class),
                    application.getEnvironment().getProperty("app.document-processing.worker.max-deliveries", Integer.class, 5), visibility)
                    .processRequest(event, context);
        }
        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            throw new IllegalArgumentException("SQS records are required");
        }
        // Any record failure fails the batch. Duplicate redelivery is handled by worker ownership.
        for (var record : event.getRecords()) {
            var message = parseMessage(record == null ? null : record.getBody());
            long documentId = message.documentId();
            int receiveCount = receiveCount(record);
            if (receiveCount > maxDeliveries) throw new IllegalStateException("SQS delivery policy mismatch; no processing authorized");
            long claimStart = System.nanoTime();
            var claim = claims.claim(documentId, message.processingJobId(), receiveCount);
            org.slf4j.LoggerFactory.getLogger(getClass()).info(
                    "Document delivery documentId={} jobId={} receiveCount={} claim={} claimMs={}",
                    documentId, message.processingJobId(), receiveCount, claim.status(), (System.nanoTime() - claimStart) / 1_000_000);
            switch (claim.status()) {
                case TERMINAL -> { /* Completed or permanently failed: stale message is handled. */ }
                case TERMINAL_FAILED -> {
                    if (visibility != null) visibility.afterTerminalFailure(record);
                    throw new IllegalStateException("Document job failed; retain message for dead-lettering");
                }
                case ALREADY_ACTIVE -> {
                    if (visibility != null && claim.leaseExpiresAt() != null) visibility.protectLease(record, claim.leaseExpiresAt());
                    throw new IllegalStateException("Document job still has an active lease; retry required");
                }
                case NOT_PROCESSABLE -> throw new IllegalStateException("Document job cannot run yet; retry required");
                case CLAIMED -> {
                    if (visibility != null) visibility.protectLease(record, claim.leaseExpiresAt());
                    DocumentProcessingWorkerExecutionService.Outcome outcome;
                    try {
                        outcome = worker.execute(documentId, claim.attemptId(), receiveCount, maxDeliveries);
                    } catch (DocumentProcessingException failure) {
                        if (visibility != null && failure.getCommittedFailure() != null) {
                            if (failure.getCommittedFailure() == DocumentProcessingFailureService.FailureResult.RETRY
                                    && receiveCount < maxDeliveries) visibility.afterRetryableFailure(record, receiveCount);
                            else visibility.afterTerminalFailure(record);
                        }
                        // A visibility change never acknowledges unfinished work, including when SQS rejects it.
                        throw failure;
                    }
                    if (outcome != DocumentProcessingWorkerExecutionService.Outcome.SUCCESS) {
                        throw new IllegalStateException("Document job did not complete with owned success; retry required");
                    }
                    // SUCCESS is handled; failed attempts propagate so their messages reach the DLQ.
                }
            }
        }
        return null;
    }

    private int receiveCount(SQSEvent.SQSMessage record) {
        try {
            int count = Integer.parseInt(record.getAttributes().get("ApproximateReceiveCount"));
            if (count < 1) throw new IllegalArgumentException();
            return count;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("SQS receive count is required");
        }
    }

    private com.ishita.tripcoordinatorplatform.service.DocumentProcessingMessage parseMessage(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("SQS body must contain a positive documentId");
        }
        var message = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(body);
        var id = message == null ? null : message.get("documentId");
        if (message == null || !message.isObject() || (message.size() != 1 && message.size() != 2) || id == null
                || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            throw new IllegalArgumentException("SQS body must contain only a positive integer documentId");
        }
        java.util.UUID jobId = null;
        var job = message.get("processingJobId");
        if (message.size() == 2 && job == null) throw new IllegalArgumentException("Unexpected SQS field");
        if (job != null && !job.isNull()) {
            if (!job.isString() || !job.asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("Invalid processing job ID");
            jobId = java.util.UUID.fromString(job.asText());
        }
        return new com.ishita.tripcoordinatorplatform.service.DocumentProcessingMessage(id.longValue(), jobId);
    }

    private static class SpringContextHolder {
        // Initialized once per execution environment and retained for warm invocations.
        private static final ConfigurableApplicationContext CONTEXT = startContext();
    }

    static SpringApplication application() {
        var application = new SpringApplication(TripCoordinatorPlatformApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // Keep API-only background work and EXPLORE services out of the Lambda context.
        application.setAdditionalProfiles("document-worker");
        application.addListeners(new StartupTimings());
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("lambdaWorker", Map.ofEntries(
                        Map.entry("spring.main.web-application-type", "none"),
                        Map.entry("app.document-processing.publisher.enabled", "false"),
                        Map.entry("app.document-processing.execution-mode", "DISABLED"),
                        Map.entry("app.document-storage.provider", "s3"),
                        Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                        Map.entry("spring.sql.init.mode", "never"),
                        // Only the API changes the schema; the worker must start fast and never migrate.
                        Map.entry("spring.flyway.enabled", "false"),
                        Map.entry("spring.jpa.show-sql", "false"),
                        Map.entry("spring.datasource.hikari.connection-timeout", "5000")))));
        application.addInitializers(context -> {
            var environment = context.getEnvironment();
            String url = environment.getProperty("spring.datasource.url");
            if (url != null && url.contains(".rds.amazonaws.com")) {
                environment.getPropertySources().addFirst(new MapPropertySource("lambdaRdsDeadlines", Map.of(
                        "spring.datasource.hikari.data-source-properties.connectTimeout", "5",
                        "spring.datasource.hikari.data-source-properties.socketTimeout", "10",
                        "spring.datasource.hikari.data-source-properties.options", "-c statement_timeout=10000 -c lock_timeout=3000")));
                if (url.matches(".*[?&]sslmode=(require|verify-ca|verify-full)(&.*)?$")) return;
                if (url.contains("sslmode=")) throw new IllegalArgumentException("Lambda RDS connection requires TLS");
                environment.getPropertySources().addFirst(new MapPropertySource("lambdaRdsTls", Map.of(
                        "spring.datasource.url", url + (url.contains("?") ? "&" : "?") + "sslmode=require")));
            }
        });
        return application;
    }

    private static ConfigurableApplicationContext startContext() {
        long started = System.nanoTime();
        var secrets = LambdaRuntimeSecrets.loadFromEnvironment();
        org.slf4j.LoggerFactory.getLogger(DocumentProcessingLambdaHandler.class).info(
                "Document worker startup stage=secrets elapsedMs={}", (System.nanoTime() - started) / 1_000_000);
        return application(secrets).run();
    }

    /** Separates Spring setup from context refresh, which includes JPA and singleton creation. */
    private static final class StartupTimings implements org.springframework.context.ApplicationListener<org.springframework.boot.context.event.SpringApplicationEvent> {
        private long started;
        private long previous;

        @Override public void onApplicationEvent(org.springframework.boot.context.event.SpringApplicationEvent event) {
            long now = System.nanoTime();
            if (event instanceof org.springframework.boot.context.event.ApplicationStartingEvent) {
                started = now;
                previous = now;
                return;
            }
            String stage;
            if (event instanceof org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent) stage = "environment-prepared";
            else if (event instanceof org.springframework.boot.context.event.ApplicationPreparedEvent) stage = "context-prepared";
            else if (event instanceof org.springframework.boot.context.event.ApplicationStartedEvent) stage = "context-refreshed";
            else if (event instanceof org.springframework.boot.context.event.ApplicationReadyEvent) stage = "ready";
            else if (event instanceof org.springframework.boot.context.event.ApplicationFailedEvent) stage = "failed";
            else return;
            org.slf4j.LoggerFactory.getLogger(DocumentProcessingLambdaHandler.class).info(
                    "Document worker startup stage={} phaseElapsedMs={} springElapsedMs={}",
                    stage, (now - previous) / 1_000_000, (now - started) / 1_000_000);
            previous = now;
        }
    }

    static SpringApplication application(Map<String, Object> secrets) {
        var application = application();
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("lambdaRuntimeSecrets", secrets)));
        return application;
    }
}
