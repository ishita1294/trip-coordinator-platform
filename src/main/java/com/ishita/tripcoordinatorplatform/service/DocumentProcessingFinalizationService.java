package com.ishita.tripcoordinatorplatform.service;

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

@Service
public class DocumentProcessingFinalizationService {

    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;

    public DocumentProcessingFinalizationService(TravelDocumentRepository documents,
                                               TravelDocumentExtractionRepository extractions) {
        this.documents = documents;
        this.extractions = extractions;
    }

    @Transactional
    public TravelDocumentProcessingResponse finalizeFlightDocument(Long documentId, UUID attemptId,
                                                                 TravelDocumentType documentType,
                                                                 String extractedData) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        if (documentType != TravelDocumentType.FLIGHT_CONFIRMATION) {
            throw new IllegalArgumentException("Flight finalization requires FLIGHT_CONFIRMATION");
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
