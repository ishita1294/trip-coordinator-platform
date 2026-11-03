package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec.Version;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Saves the classified category and one-time extractor choice before any extraction request. */
@Service
public class DocumentExtractionRoutingService {
    public record Decision(Version contract, TravelDocumentType documentType) {}
    private final TravelDocumentRepository documents;
    private final DocumentProcessingOutboxRepository jobs;

    public DocumentExtractionRoutingService(TravelDocumentRepository documents, DocumentProcessingOutboxRepository jobs) {
        this.documents=documents;this.jobs=jobs;
    }

    @Transactional
    public Decision resolve(Long documentId, UUID jobId, UUID attemptId, TravelDocumentType classifiedType) {
        var document=documents.findForProcessingFailure(documentId).orElseThrow(DocumentProcessingException::ownershipLost);
        if (jobId == null || attemptId == null || document.getProcessingStatus() != TravelDocumentProcessingStatus.PROCESSING
                || !Objects.equals(jobId,document.getProcessingJobId()) || !Objects.equals(attemptId,document.getProcessingAttemptId()))
            throw DocumentProcessingException.ownershipLost();
        var job=jobs.findByDocumentIdAndProcessingJobId(documentId,jobId)
                .orElseThrow(()->DocumentProcessingException.permanentInput("Processing job record is missing"));
        Version saved;
        try {saved=Version.fromJobContract(job.getExtractionContract());}
        catch (IllegalArgumentException unknown) {throw DocumentProcessingException.permanentInput("Unsupported extraction contract");}
        if(saved==Version.SOURCE_BOOKING_V1_REVIEW) {
            if(classifiedType!=TravelDocumentType.TOUR_BOOKING&&classifiedType!=TravelDocumentType.ATTRACTION_TICKET)
                throw DocumentProcessingException.permanentInput("Native evaluation supports attraction/tour documents only");
            document.setDocumentType(classifiedType); // Same locked ownership boundary; retries reuse classification.
            return new Decision(saved,classifiedType);
        }
        if (saved != Version.AUTO_V3_4) return new Decision(saved,document.getDocumentType());

        var selected=classifiedType == null ? Version.UNSUPPORTED : switch (classifiedType) {
            case ATTRACTION_TICKET, TOUR_BOOKING -> Version.SEMANTIC_V3_4;
            case FLIGHT_CONFIRMATION -> Version.FLIGHT_V1;
            default -> Version.UNSUPPORTED;
        };
        document.setDocumentType(classifiedType);
        // The bulk update flushes the category first and repeats job/attempt ownership checks.
        // Both writes roll back if the pending contract cannot be resolved.
        if (jobs.resolveExtractionContract(documentId,jobId,attemptId,selected.name()) != 1)
            throw DocumentProcessingException.ownershipLost();
        return new Decision(selected,classifiedType);
    }
}
