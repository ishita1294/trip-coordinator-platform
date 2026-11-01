package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentProcessingFailureService {

    private final TravelDocumentRepository documents;
    private final com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository outbox;

    public DocumentProcessingFailureService(TravelDocumentRepository documents,
            com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository outbox) {
        this.outbox = outbox;
        this.documents = documents;
    }

    public enum FailureResult { OWNERSHIP_LOST, RETRY, FAILED }
    public static final String EXTRACTION_TIMEOUT = "EXTRACTION_TIMEOUT";

    // Row lock serializes the decision with claims/finalization. Only the current attempt may write.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public FailureResult recordFailure(Long documentId, UUID attemptId, String code,
                                       boolean terminal, int invalidOutputLimit, int maxAttempts) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        var document = documents.findForProcessingFailure(documentId).orElse(null);
        if (document == null || document.getProcessingStatus() != TravelDocumentProcessingStatus.PROCESSING
                || !attemptId.equals(document.getProcessingAttemptId())) return FailureResult.OWNERSHIP_LOST;
        document.setProcessingFailureCount(document.getProcessingFailureCount() + 1);
        if ("INVALID_EXTRACTION".equals(code))
            document.setProcessingInvalidOutputCount(document.getProcessingInvalidOutputCount() + 1);
        if (EXTRACTION_TIMEOUT.equals(code))
            document.setProcessingExtractionTimeoutCount(document.getProcessingExtractionTimeoutCount() + 1);
        boolean failed = terminal || document.getProcessingDeliveryCount() >= maxAttempts
                || document.getProcessingAttemptCount() >= maxAttempts
                || document.getProcessingFailureCount() >= maxAttempts || document.getProcessingInvalidOutputCount() >= invalidOutputLimit
                // This durable per-job count survives restarts and intervening non-timeout failures.
                || (EXTRACTION_TIMEOUT.equals(code) && document.getProcessingExtractionTimeoutCount() >= 2);
        document.setProcessingStatus(failed ? TravelDocumentProcessingStatus.FAILED : TravelDocumentProcessingStatus.QUEUED);
        document.setProcessingLastErrorCode(code);
        document.setProcessingFailedAt(failed ? java.time.Instant.now() : null);
        document.setProcessingAttemptId(null);
        document.setProcessingStartedAt(null);
        if (failed) markJobFailed(document);
        return failed ? FailureResult.FAILED : FailureResult.RETRY;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public boolean recoverExpiredFinalAttempt(Long documentId, UUID jobId, UUID attemptId,
                                               java.time.Instant cutoff, int maxDeliveries) {
        var document = documents.findForProcessingFailure(documentId).orElse(null);
        if (document == null || document.getProcessingStatus() != TravelDocumentProcessingStatus.PROCESSING
                || attemptId == null || !attemptId.equals(document.getProcessingAttemptId())
                || !Objects.equals(jobId, document.getProcessingJobId())
                || document.getProcessingStartedAt() == null || document.getProcessingStartedAt().isAfter(cutoff)
                || (document.getProcessingDeliveryCount() < maxDeliveries && document.getProcessingAttemptCount() < maxDeliveries)) return false;
        document.setProcessingFailureCount(document.getProcessingFailureCount() + 1);
        document.setProcessingLastErrorCode("RETRIES_EXHAUSTED");
        document.setProcessingStatus(TravelDocumentProcessingStatus.FAILED);
        document.setProcessingFailedAt(java.time.Instant.now());
        document.setProcessingAttemptId(null);document.setProcessingStartedAt(null);
        markJobFailed(document);
        return true;
    }

    private void markJobFailed(com.ishita.tripcoordinatorplatform.model.TravelDocument document) {
        if (document.getProcessingJobId() == null) {
            // Legacy bodies lack a job reference; no new queue request can occur while the document lock is held.
            outbox.markLatestLegacyJobFailed(document.getId(),document.getProcessingFailedAt());
        } else if (outbox.markGenerationFailed(document.getId(),document.getProcessingJobId(),document.getProcessingFailedAt()) != 1) {
            throw new IllegalStateException("Processing job provenance is unavailable; failure transaction rolled back");
        }
    }

}
