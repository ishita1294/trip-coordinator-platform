package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec;
import com.ishita.tripcoordinatorplatform.config.DocumentProcessingMode;
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
    private final boolean processingEnabled;
    // New jobs resolve the versioned routing policy after classification. Historical selections are unchanged.
    @org.springframework.beans.factory.annotation.Value("${app.document-processing.extraction-contract:AUTO_V3_4}")
    private TourExtractionCodec.Version extractionContract =
            TourExtractionCodec.Version.AUTO_V3_4;
    @org.springframework.beans.factory.annotation.Autowired private com.ishita.tripcoordinatorplatform.repository.BookingConfirmationReceiptRepository receipts;

    public DocumentProcessingQueueService(TravelDocumentRepository documents,
            DocumentProcessingOutboxRepository outbox, boolean legacyPublisherEnabled) {
        this(documents, outbox, legacyPublisherEnabled, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentProcessingQueueService(TravelDocumentRepository documents,
            DocumentProcessingOutboxRepository outbox,
            @org.springframework.beans.factory.annotation.Value("${app.document-processing.publisher.enabled:false}") boolean legacyPublisherEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.document-processing.execution-mode:}") String mode) {
        this.documents = documents;
        this.outbox = outbox;
        this.processingEnabled = DocumentProcessingMode.resolve(mode, legacyPublisherEnabled)
                != DocumentProcessingMode.DISABLED;
    }

    @org.springframework.beans.factory.annotation.Value("${app.document-processing.source-booking-review-enabled:${SOURCE_BOOKING_REVIEW_ENABLED:false}}")
    private boolean sourceBookingReviewEnabled;
    public boolean sourceBookingReviewEnabled(){return sourceBookingReviewEnabled;}
    @Transactional
    public TravelDocumentProcessingResponse queueDocument(Long tripId, Long documentId) {return queueDocument(tripId,documentId,false);}
    @Transactional
    public TravelDocumentProcessingResponse queueDocument(Long tripId,Long documentId,boolean nativeEvaluation) {
        if(!nativeEvaluation&&extractionContract==TourExtractionCodec.Version.SOURCE_BOOKING_V1_REVIEW)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Native review requires explicit per-job opt-in, never global routing.");
        if(nativeEvaluation&&!sourceBookingReviewEnabled)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Native review evaluation is not enabled on this server.");
        var before=documents.findForConfirmation(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Native documents never re-enter a legacy extractor, including through the ordinary Process endpoint.
        if(before.getProcessingJobId()!=null && outbox.findByDocumentIdAndProcessingJobId(documentId,before.getProcessingJobId())
                .map(j->TourExtractionCodec.Version.SOURCE_BOOKING_V1_REVIEW.name().equals(j.getExtractionContract())).orElse(false))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Native evaluation documents are read-only; upload a new evaluation document instead.");
        if(nativeEvaluation&&(before.getProcessingStatus()!=TravelDocumentProcessingStatus.UPLOADED||before.getProcessingJobId()!=null))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Native evaluation is available only for a new, unprocessed upload.");
        if (!processingEnabled) throw new DocumentProcessingPausedException();
        if(receipts!=null){
            documents.findForConfirmation(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Travel document not found"));
            if(receipts.existsForDocument(documentId))throw new ResponseStatusException(HttpStatus.CONFLICT,"Confirmed source bookings cannot be reprocessed; continue reviewing their existing extraction.");
        }
        // Only one concurrent request can change an allowed status to QUEUED.
        var jobId = java.util.UUID.randomUUID();
        int claimed = documents.claimForQueue(documentId, tripId,
                TravelDocumentProcessingStatus.QUEUED, jobId, QUEUEABLE_STATUSES);

        var document = documents.findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document not found"));
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Travel document cannot be queued in its current status");
        }

        DocumentProcessingOutbox entry = new DocumentProcessingOutbox();
        entry.setDocumentId(documentId);
        entry.setProcessingJobId(jobId);
        entry.setExtractionContract((nativeEvaluation?TourExtractionCodec.Version.SOURCE_BOOKING_V1_REVIEW:extractionContract).name());
        entry.setStatus(DocumentProcessingOutboxStatus.PENDING);
        entry.setCreatedAt(Instant.now());
        outbox.save(entry);

        return new TravelDocumentProcessingResponse(documentId, document.getProcessingStatus(),
                document.getDocumentType(), null);
    }
}
