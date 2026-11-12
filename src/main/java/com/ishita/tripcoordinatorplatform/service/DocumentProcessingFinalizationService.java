package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException;
import com.ishita.tripcoordinatorplatform.ai.document.TourReadingValidator;
import com.ishita.tripcoordinatorplatform.ai.document.TourReading;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentExtraction;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentProcessingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec;

@Service
public class DocumentProcessingFinalizationService {

    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    @org.springframework.beans.factory.annotation.Autowired private com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository jobs;

    public DocumentProcessingFinalizationService(TravelDocumentRepository documents,
                                               TravelDocumentExtractionRepository extractions) {
        this.documents = documents;
        this.extractions = extractions;
    }

    @Transactional(timeout = 10)
    public TravelDocumentProcessingResponse finalizeDocument(Long documentId, UUID attemptId,
                                                                 TravelDocumentType documentType,
                                                                 String extractedData) {
        if (isTour(documentType) && new TourExtractionCodec(new tools.jackson.databind.json.JsonMapper()).schemaVersion(extractedData) != 1)
            throw new IllegalArgumentException("Semantic output requires source-bound semantic finalization");
        return persist(documentId, attemptId, documentType, extractedData);
    }

    /** Validates original-byte provenance before the existing fenced, immutable insert. */
    @Transactional(timeout = 10)
    public TravelDocumentProcessingResponse finalizeTourReading(Long documentId, UUID attemptId,
            TravelDocumentType documentType,
            TourReading result, String expectedSourceSha256) {
        if (!isTour(documentType)) throw new IllegalArgumentException("Semantic output requires an attraction/tour document");
        if (result == null || result.provenance() == null
                || !TourReading.isCurrentContract(result.provenance().promptVersion()))
            throw new InvalidDocumentExtractionException("Expected V3.4 extraction");
        new TourReadingValidator().validate(result, expectedSourceSha256);
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(result);
        if (json.length() > 100_000)
            throw new InvalidDocumentExtractionException("Semantic extraction exceeds existing storage capacity");
        return persist(documentId, attemptId, documentType, json);
    }

    /** Read-only native envelope; source digest/job identity are assigned by this fenced transaction, never the model. */
    @Transactional(timeout=10)
    public TravelDocumentProcessingResponse finalizeSourceBooking(Long documentId,UUID attemptId,TravelDocumentType type,String raw,
            com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput original) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Native finalization requires an active transaction");
        var document=documents.findForProcessingFailure(documentId).orElseThrow(DocumentProcessingException::ownershipLost);
        if(attemptId==null||!attemptId.equals(document.getProcessingAttemptId())||document.getProcessingStatus()!=com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus.PROCESSING||!isTour(type))
            throw DocumentProcessingException.ownershipLost();
        var job=jobs.findByDocumentIdAndProcessingJobId(documentId,document.getProcessingJobId()).orElseThrow(DocumentProcessingException::ownershipLost);
        if(!TourExtractionCodec.Version.SOURCE_BOOKING_V1_REVIEW.name().equals(job.getExtractionContract()))throw DocumentProcessingException.ownershipLost();
        var mapper=new tools.jackson.databind.json.JsonMapper();
        var stored=com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.create(raw,mapper,original,document.getProcessingJobId(),document.getUploadedAt(),job.getCreatedAt(),Instant.now());
        String json=mapper.writeValueAsString(stored);if(json.length()>100_000)throw new InvalidDocumentExtractionException("Native review exceeds immutable storage bound");
        var response=persist(documentId,attemptId,type,json);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCommit(){
                Instant committed=Instant.now();long total=java.time.Duration.between(document.getUploadedAt(),committed).toMillis();
                org.slf4j.LoggerFactory.getLogger(DocumentProcessingFinalizationService.class).info(
                    "Document native review committed documentId={} jobId={} attemptId={} extractionId={} uploadCompletedAt={} queuedAt={} persistedReviewAt={} uploadToReviewMs={}",
                    documentId,stored.provenance().processingJobId(),attemptId,response.extractionId(),document.getUploadedAt(),job.getCreatedAt(),committed,total>=0?total:null);
            }
        });
        return response;
    }

    private static boolean isTour(TravelDocumentType type) {
        return type == TravelDocumentType.ATTRACTION_TICKET || type == TravelDocumentType.TOUR_BOOKING;
    }

    private TravelDocumentProcessingResponse persist(Long documentId, UUID attemptId,
            TravelDocumentType documentType, String extractedData) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        if (documentType != TravelDocumentType.FLIGHT_CONFIRMATION
                && documentType != TravelDocumentType.ATTRACTION_TICKET
                && documentType != TravelDocumentType.TOUR_BOOKING) {
            throw new IllegalArgumentException("Extraction finalization is not implemented for this document type");
        }
        // This update locks the row until commit and fences every subsequent write in this transaction.
        if (documents.completeProcessingAttempt(documentId, attemptId, documentType) != 1) {
            throw DocumentProcessingException.ownershipLost();
        }

        var document = documents.findById(documentId).orElseThrow();
        TravelDocumentExtraction extraction = new TravelDocumentExtraction();
        extraction.setTravelDocument(document);
        extraction.setExtractedData(extractedData);
        extraction.setCreatedAt(Instant.now());
        Long extractionId = extractions.save(extraction).getId();

        return new TravelDocumentProcessingResponse(documentId, document.getProcessingStatus(),
                document.getDocumentType(), extractionId);
    }
}
