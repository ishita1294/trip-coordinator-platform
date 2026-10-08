package com.ishita.tripcoordinatorplatform.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ishita.tripcoordinatorplatform.TripCoordinatorPlatformApplication;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingWorkerClaimService;
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

    public DocumentProcessingLambdaHandler() {
        this(SpringContextHolder.CONTEXT.getBean(DocumentProcessingWorkerClaimService.class),
                SpringContextHolder.CONTEXT.getBean(DocumentProcessingWorkerExecutionService.class),
                SpringContextHolder.CONTEXT.getBean(JsonMapper.class));
    }

    // Tests inject services without starting Spring or connecting to infrastructure.
    DocumentProcessingLambdaHandler(DocumentProcessingWorkerClaimService claims,
                                    DocumentProcessingWorkerExecutionService worker, JsonMapper mapper) {
        this.claims = claims;
        this.worker = worker;
        this.mapper = mapper;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            throw new IllegalArgumentException("SQS records are required");
        }
        // Any record failure fails the batch. Duplicate redelivery is handled by worker ownership.
        for (var record : event.getRecords()) {
            long documentId = parseDocumentId(record == null ? null : record.getBody());
            var claim = claims.claim(documentId);
            switch (claim.status()) {
                case TERMINAL -> { /* Completed or permanently failed: stale message is handled. */ }
                case ALREADY_ACTIVE, NOT_PROCESSABLE ->
                        throw new IllegalStateException("Document job cannot run yet; retry required");
                case CLAIMED -> {
                    var outcome = worker.execute(documentId, claim.attemptId());
                    if (outcome == DocumentProcessingWorkerExecutionService.Outcome.OWNERSHIP_LOST) {
                        throw new IllegalStateException("Document job lost ownership; retry required");
                    }
                    // SUCCESS and PERMANENT_FAILURE are handled; retryable exceptions propagate.
                }
            }
        }
        return null;
    }

    private long parseDocumentId(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("SQS body must contain a positive documentId");
        }
        var message = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(body);
        var id = message == null ? null : message.get("documentId");
        if (message == null || !message.isObject() || message.size() != 1 || id == null
                || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            throw new IllegalArgumentException("SQS body must contain only a positive integer documentId");
        }
        return id.longValue();
    }

    private static class SpringContextHolder {
        // Initialized once per execution environment and retained for warm invocations.
        private static final ConfigurableApplicationContext CONTEXT = startContext();
    }

    static SpringApplication application() {
        var application = new SpringApplication(TripCoordinatorPlatformApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("lambdaWorker", Map.of(
                        "spring.main.web-application-type", "none",
                        "app.document-processing.publisher.enabled", "false",
                        "app.document-storage.provider", "s3"))));
        return application;
    }

    private static ConfigurableApplicationContext startContext() {
        return application(LambdaRuntimeSecrets.loadFromEnvironment()).run();
    }

    static SpringApplication application(Map<String, Object> secrets) {
        var application = application();
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("lambdaRuntimeSecrets", secrets)));
        return application;
    }
}
