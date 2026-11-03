package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentProcessingBudget;

import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
public class DocumentProcessingWorkerClaimService {

    private final TravelDocumentRepository documents;
    private final com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository outbox;
    private final Duration leaseDuration;
    private final Clock clock;
    private final int maxDeliveries;

    @Autowired
    public DocumentProcessingWorkerClaimService(
            TravelDocumentRepository documents,
            com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository outbox,
            @Value("${app.document-processing.worker.lease-seconds:240}") long leaseSeconds,
            @Value("${app.document-processing.worker.max-deliveries:5}") int maxDeliveries
    ) {
        this(documents, outbox, leaseSeconds, Clock.systemUTC(), maxDeliveries);
        if (leaseSeconds < DocumentProcessingBudget.LEASE_SECONDS)
            throw new IllegalArgumentException("Processing lease must allow the full Lambda execution and cleanup budget");
    }

    DocumentProcessingWorkerClaimService(TravelDocumentRepository documents,
            com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository outbox,
            long leaseSeconds, Clock clock, int maxDeliveries) {
        this.outbox = outbox;
        if (maxDeliveries < 1) throw new IllegalArgumentException("Maximum deliveries must be positive");
        this.maxDeliveries = maxDeliveries;
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("Document processing lease seconds must be greater than zero");
        }
        this.documents = documents;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.clock = clock;
    }

    /** Locks briefly to obtain the next delivery count, then uses the shared claim rules. */
    @Transactional
    public DocumentProcessingWorkerClaimResult claimLocal(Long documentId, UUID jobId) {
        var document = documents.findForProcessingFailure(documentId).orElse(null);
        if (document == null || !java.util.Objects.equals(jobId, document.getProcessingJobId()))
            return DocumentProcessingWorkerClaimResult.TERMINAL;
        if (document.getProcessingAttemptCount() >= maxDeliveries)
            return DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE;
        var result = claim(documentId, jobId, document.getProcessingAttemptCount() + 1, false);
        if (result.status() == DocumentProcessingWorkerClaimResult.Status.CLAIMED) {
            var job = (jobId == null ? outbox.findFirstByDocumentIdAndProcessingJobIdIsNullOrderByIdDesc(documentId)
                    : outbox.findByDocumentIdAndProcessingJobId(documentId, jobId))
                    .orElseThrow(() -> new IllegalStateException("Processing job record is missing"));
            if (job.getProcessingFailedAt() != null) throw new IllegalStateException("Processing job already failed");
            if (job.getStatus() == com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus.PENDING) {
                job.setStatus(com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus.PUBLISHED);
                job.setPublishedAt(clock.instant());
                outbox.save(job);
            }
        }
        return result;
    }

    /** Keeps a live local attempt from being reclaimed while its provider request runs. */
    @Transactional
    public boolean renewLease(Long documentId, UUID jobId, UUID attemptId) {
        return documents.renewProcessingLease(documentId, jobId, attemptId, clock.instant()) == 1;
    }

    @Transactional
    public DocumentProcessingWorkerClaimResult claim(Long documentId) { return claim(documentId, null, 1); }

    @Transactional
    public DocumentProcessingWorkerClaimResult claim(Long documentId, UUID jobId) { return claim(documentId, jobId, 1); }

    @Transactional
    public DocumentProcessingWorkerClaimResult claim(Long documentId, UUID jobId, int receiveCount) {
        return claim(documentId, jobId, receiveCount, true);
    }

    private DocumentProcessingWorkerClaimResult claim(Long documentId, UUID jobId, int receiveCount, boolean sqsDelivery) {
        if (receiveCount < 1) throw new IllegalArgumentException("Receive count must be positive");
        var now = clock.instant();
        UUID attemptId = UUID.randomUUID();
        if (documents.claimQueuedForProcessing(documentId, now, attemptId, jobId, receiveCount, maxDeliveries) == 1) {
            return new DocumentProcessingWorkerClaimResult(DocumentProcessingWorkerClaimResult.Status.CLAIMED, attemptId, now.plus(leaseDuration));
        }
        UUID reclaimedAttemptId = UUID.randomUUID();
        if (documents.reclaimStaleProcessing(documentId, now, now.minus(leaseDuration), reclaimedAttemptId, jobId, receiveCount, maxDeliveries) == 1) {
            return new DocumentProcessingWorkerClaimResult(DocumentProcessingWorkerClaimResult.Status.CLAIMED, reclaimedAttemptId, now.plus(leaseDuration));
        }
        return documents.findById(documentId)
                .map(document -> {
                    if (!java.util.Objects.equals(document.getProcessingJobId(), jobId)) {
                        boolean failed = jobId == null
                                ? outbox.existsByDocumentIdAndProcessingJobIdIsNullAndProcessingFailedAtIsNotNull(documentId)
                                : outbox.existsByDocumentIdAndProcessingJobIdAndProcessingFailedAtIsNotNull(documentId,jobId);
                        return failed ? DocumentProcessingWorkerClaimResult.TERMINAL_FAILED : DocumentProcessingWorkerClaimResult.TERMINAL;
                    }
                    return switch (document.getProcessingStatus()) {
                    case PROCESSING -> {
                        if (sqsDelivery) documents.observeActiveDelivery(documentId, jobId, receiveCount);
                        yield new DocumentProcessingWorkerClaimResult(DocumentProcessingWorkerClaimResult.Status.ALREADY_ACTIVE, null,
                                document.getProcessingStartedAt() == null ? null : document.getProcessingStartedAt().plus(leaseDuration));
                    }
                    case FAILED -> DocumentProcessingWorkerClaimResult.TERMINAL_FAILED;
                    case REVIEW_REQUIRED, PROCESSED -> DocumentProcessingWorkerClaimResult.TERMINAL;
                    default -> DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE;
                }; })
                .orElse(DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE);
    }

}
