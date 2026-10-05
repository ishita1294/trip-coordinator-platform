package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus;
import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "app.document-processing.publisher.enabled", havingValue = "true")
public class DocumentProcessingOutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessingOutboxPublisher.class);

    private final DocumentProcessingOutboxRepository outbox;
    private final SqsClient sqsClient;
    private final JsonMapper jsonMapper;
    private final String queueUrl;

    public DocumentProcessingOutboxPublisher(DocumentProcessingOutboxRepository outbox,
                                             SqsClient sqsClient,
                                             JsonMapper jsonMapper,
                                             @Value("${app.document-processing.sqs.queue-url:}") String queueUrl) {
        this.outbox = outbox;
        this.sqsClient = sqsClient;
        this.jsonMapper = jsonMapper;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${app.document-processing.publisher.delay-ms:2000}")
    public void publishPending() {
        var entries = outbox.findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING);
        for (var entry : entries) {
            if (entry.getStatus() != DocumentProcessingOutboxStatus.PENDING) {
                continue;
            }
            try {
                String body = jsonMapper.writeValueAsString(new DocumentProcessingMessage(entry.getDocumentId()));
                // No database transaction is held while waiting for SQS.
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .build());

                // If this save fails, the persisted row remains PENDING and can be sent again.
                entry.setStatus(DocumentProcessingOutboxStatus.PUBLISHED);
                entry.setPublishedAt(Instant.now());
                outbox.save(entry);
            } catch (RuntimeException exception) {
                // Avoid logging message contents or potentially sensitive provider error details.
                logger.warn("Failed to publish document-processing outbox row {} ({}); retry on a later run",
                        entry.getId(), exception.getClass().getSimpleName());
            }
        }
    }
}
