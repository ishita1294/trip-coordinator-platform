package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
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
    private final Duration leaseDuration;
    private final Clock clock;

    @Autowired
    public DocumentProcessingWorkerClaimService(
            TravelDocumentRepository documents,
            @Value("${app.document-processing.worker.lease-seconds:120}") long leaseSeconds
    ) {
        this(documents, leaseSeconds, Clock.systemUTC());
    }

    DocumentProcessingWorkerClaimService(TravelDocumentRepository documents, long leaseSeconds, Clock clock) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("Document processing lease seconds must be greater than zero");
        }
        this.documents = documents;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.clock = clock;
    }

    @Transactional
    public DocumentProcessingWorkerClaimResult claim(Long documentId) {
        var now = clock.instant();
        UUID attemptId = UUID.randomUUID();
        if (documents.claimQueuedForProcessing(documentId, now, attemptId) == 1) {
            return new DocumentProcessingWorkerClaimResult(DocumentProcessingWorkerClaimResult.Status.CLAIMED, attemptId);
        }
        UUID reclaimedAttemptId = UUID.randomUUID();
        if (documents.reclaimStaleProcessing(documentId, now, now.minus(leaseDuration), reclaimedAttemptId) == 1) {
            return new DocumentProcessingWorkerClaimResult(DocumentProcessingWorkerClaimResult.Status.CLAIMED, reclaimedAttemptId);
        }
        return documents.findById(documentId)
                .map(document -> switch (document.getProcessingStatus()) {
                    case PROCESSING -> DocumentProcessingWorkerClaimResult.ALREADY_ACTIVE;
                    case REVIEW_REQUIRED, PROCESSED, FAILED -> DocumentProcessingWorkerClaimResult.TERMINAL;
                    default -> DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE;
                })
                .orElse(DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE);
    }

}
