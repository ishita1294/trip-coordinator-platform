package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

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
        if (documents.claimQueuedForProcessing(documentId, now) == 1) {
            return DocumentProcessingWorkerClaimResult.CLAIMED;
        }
        if (documents.reclaimStaleProcessing(documentId, now, now.minus(leaseDuration)) == 1) {
            return DocumentProcessingWorkerClaimResult.CLAIMED;
        }
        return documents.findById(documentId)
                .filter(document -> document.getProcessingStatus() == TravelDocumentProcessingStatus.PROCESSING)
                .map(document -> DocumentProcessingWorkerClaimResult.ALREADY_ACTIVE)
                .orElse(DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE);
    }

    @Transactional
    public void clearCompletedLease(Long documentId) {
        // Never clear ownership while a document is still PROCESSING.
        documents.clearCompletedProcessingLease(documentId);
    }
}
