package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentProcessingResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class DocumentProcessingQueueService {

    private static final List<TravelDocumentProcessingStatus> QUEUEABLE_STATUSES = List.of(
            TravelDocumentProcessingStatus.UPLOADED,
            TravelDocumentProcessingStatus.REVIEW_REQUIRED,
            TravelDocumentProcessingStatus.FAILED
    );

    private final TravelDocumentRepository documents;
    private final DocumentProcessingOutboxRepository outbox;

    public DocumentProcessingQueueService(TravelDocumentRepository documents,
                                         DocumentProcessingOutboxRepository outbox) {
        this.documents = documents;
        this.outbox = outbox;
    }

    @Transactional
    public TravelDocumentProcessingResponse queueDocument(Long tripId, Long documentId) {
        // Only one concurrent request can change an allowed status to QUEUED.
        int claimed = documents.claimForQueue(documentId, tripId,
                TravelDocumentProcessingStatus.QUEUED, QUEUEABLE_STATUSES);

        var document = documents.findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document not found"));
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Travel document cannot be queued in its current status");
        }

        DocumentProcessingOutbox entry = new DocumentProcessingOutbox();
        entry.setDocumentId(documentId);
        entry.setStatus(DocumentProcessingOutboxStatus.PENDING);
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);

        return new TravelDocumentProcessingResponse(documentId, document.getProcessingStatus(),
                document.getDocumentType(), null);
    }
}
