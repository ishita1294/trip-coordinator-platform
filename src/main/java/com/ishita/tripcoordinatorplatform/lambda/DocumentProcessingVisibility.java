package com.ishita.tripcoordinatorplatform.lambda;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentProcessingBudget;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Controls only the current SQS delivery. It never sends, receives or deletes messages. */
final class DocumentProcessingVisibility {
    private static final int[] RETRY_SECONDS = {15, 30, 60, 120};
    private static final int LEASE_MARGIN_SECONDS = 30;
    private final SqsClient sqs;
    private final String queueUrl;
    private final String queueArn;
    private final Clock clock;

    DocumentProcessingVisibility(SqsClient sqs, String queueUrl, Clock clock) {
        if (queueUrl == null || queueUrl.isBlank()) throw new IllegalArgumentException("Processing queue URL is required");
        var uri = URI.create(queueUrl);
        var host = uri.getHost() == null ? new String[0] : uri.getHost().split("\\.");
        var path = uri.getPath().split("/");
        if (!"https".equals(uri.getScheme()) || host.length != 4 || !host[0].equals("sqs")
                || !host[2].equals("amazonaws") || !host[3].equals("com")
                || path.length != 3 || !path[1].matches("[0-9]{12}") || path[2].isBlank()
                || uri.getQuery() != null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Expected the configured AWS SQS queue URL");
        this.sqs = sqs; this.queueUrl = queueUrl; this.clock = clock;
        this.queueArn = "arn:aws:sqs:" + host[1] + ":" + path[1] + ":" + path[2];
    }

    static DocumentProcessingVisibility fromEnvironment() {
        return new DocumentProcessingVisibility(SqsClient.builder().overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(3)).apiCallAttemptTimeout(Duration.ofSeconds(2))
                .retryPolicy(RetryPolicy.none())).build(),
                System.getenv("DOCUMENT_PROCESSING_SQS_QUEUE_URL"), Clock.systemUTC());
    }

    /** Covers a killed Spring startup without shortening protection below the whole invocation lifetime. */
    void protectStartup(SQSEvent.SQSMessage message, int remainingMillis) {
        if (remainingMillis <= 0 || remainingMillis > DocumentProcessingBudget.LAMBDA_SECONDS * 1000)
            throw new IllegalArgumentException("Lambda remaining time exceeds the supported processing budget");
        int seconds = (int) Math.ceil(remainingMillis / 1000.0) + 60;
        if (!change(message, seconds, "STARTUP_GUARD"))
            throw new IllegalStateException("Cannot protect processing delivery; no worker execution authorized");
    }

    /** Expiry follows the committed claim, so a killed attempt is redelivered after its lease is stale. */
    void protectLease(SQSEvent.SQSMessage message, Instant expiresAt) {
        if (expiresAt == null) throw new IllegalArgumentException("Claim lease expiry is required");
        int seconds = (int) Math.max(1, Math.ceil(Duration.between(clock.instant(), expiresAt).toMillis() / 1000.0)
                + LEASE_MARGIN_SECONDS);
        if (seconds > 43200 || !change(message, seconds, "LEASE_GUARD"))
            throw new IllegalStateException("Cannot protect processing lease; no worker execution authorized");
    }

    void afterRetryableFailure(SQSEvent.SQSMessage message, int receiveCount) {
        if (receiveCount < 1 || receiveCount >= 5) throw new IllegalArgumentException("Retry delivery is exhausted");
        change(message, RETRY_SECONDS[receiveCount - 1], "COMMITTED_RETRY");
    }

    void afterTerminalFailure(SQSEvent.SQSMessage message) { change(message, 15, "TERMINAL_DLQ"); }

    private boolean change(SQSEvent.SQSMessage message, int seconds, String reason) {
        if (message == null || !queueArn.equals(message.getEventSourceArn())
                || !"aws:sqs".equals(message.getEventSource())
                || message.getReceiptHandle() == null || message.getReceiptHandle().isBlank())
            throw new IllegalArgumentException("Expected a current receipt from the configured processing queue");
        try {
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(queueUrl)
                    .receiptHandle(message.getReceiptHandle()).visibilityTimeout(seconds).build());
            LoggerFactory.getLogger(getClass()).info("Document delivery visibility reason={} delaySeconds={}", reason, seconds);
            return true;
        } catch (RuntimeException failure) {
            // Receipts and AWS error bodies can contain private delivery capabilities. Never log either.
            LoggerFactory.getLogger(getClass()).warn("Document delivery visibility unavailable reason={} causeClass={}",
                    reason, failure.getClass().getSimpleName());
            return false;
        }
    }
}
