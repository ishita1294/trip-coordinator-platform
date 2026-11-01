package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentProcessingBudget;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.ishita.tripcoordinatorplatform.config.DocumentProcessingMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;

/** Recovers a killed final invocation; never receives, deletes or replays SQS/DLQ messages. */
@Component
@ConditionalOnExpression(DocumentProcessingMode.ENABLED_CONDITION)
public class DocumentProcessingExhaustionRecovery {
    private final TravelDocumentRepository documents;
    private final DocumentProcessingFailureService failures;
    private final int maxDeliveries;
    private final Duration lease;
    public DocumentProcessingExhaustionRecovery(TravelDocumentRepository documents,DocumentProcessingFailureService failures,
            @Value("${app.document-processing.worker.max-deliveries:5}") int maxDeliveries,
            @Value("${app.document-processing.worker.lease-seconds:240}") long leaseSeconds) {
        // A lease must outlast the complete Lambda invocation, including cold start and cleanup.
        if(maxDeliveries<1 || leaseSeconds<DocumentProcessingBudget.LEASE_SECONDS) throw new IllegalArgumentException("Unsafe exhaustion recovery configuration");
        this.documents=documents;this.failures=failures;this.maxDeliveries=maxDeliveries;this.lease=Duration.ofSeconds(leaseSeconds);
    }
    @Scheduled(fixedDelayString="${app.document-processing.worker.recovery-delay-ms:30000}")
    public void recover() {
        var cutoff=Instant.now().minus(lease);
        var candidates=documents.findExhaustedProcessingCandidates(cutoff,maxDeliveries,org.springframework.data.domain.PageRequest.of(0,20));
        for(var document:candidates) {
            if(failures.recoverExpiredFinalAttempt(document.getId(),document.getProcessingJobId(),document.getProcessingAttemptId(),cutoff,maxDeliveries))
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Recovered expired final document attempt documentId={}",document.getId());
        }
    }
}
