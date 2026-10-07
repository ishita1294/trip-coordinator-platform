package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassification;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassifier;
import com.ishita.tripcoordinatorplatform.ai.document.FlightDocumentExtractor;
import com.ishita.tripcoordinatorplatform.ai.document.FlightExtractionResult;
import com.ishita.tripcoordinatorplatform.ai.document.FlightExtractionValidator;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentExtraction;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentExtractionResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentExtractionSummaryResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentProcessingResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentSummaryResponse;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.Objects;
import java.util.List;

import static com.ishita.tripcoordinatorplatform.model.TravelDocumentType.TRAIN_TICKET;

@Service
public class TravelDocumentProcessingService {

    private final TravelDocumentRepository travelDocumentRepository;
    private final DocumentClassifier documentClassifier;
    private final DocumentStorage documentStorage;
    private final FlightDocumentExtractor flightDocumentExtractor;
    private final FlightExtractionValidator flightExtractionValidator;
    private final TravelDocumentExtractionRepository travelDocumentExtractionRepository;
    private final JsonMapper jsonMapper;
    private final DocumentProcessingFinalizationService finalizationService;

    public TravelDocumentProcessingService(
            TravelDocumentRepository travelDocumentRepository,
            DocumentClassifier documentClassifier,
            DocumentStorage documentStorage,
            FlightDocumentExtractor flightDocumentExtractor,
            FlightExtractionValidator flightExtractionValidator,
            TravelDocumentExtractionRepository travelDocumentExtractionRepository,
            JsonMapper jsonMapper,
            DocumentProcessingFinalizationService finalizationService
    ) {
        this.travelDocumentRepository = travelDocumentRepository;
        this.documentClassifier = documentClassifier;
        this.documentStorage = documentStorage;
        this.flightDocumentExtractor = flightDocumentExtractor;
        this.flightExtractionValidator = flightExtractionValidator;
        this.travelDocumentExtractionRepository = travelDocumentExtractionRepository;
        this.jsonMapper = jsonMapper;
        this.finalizationService = finalizationService;
    }

    // Call only after a successful claim, using the exact token returned by that claim.
    // Storage, AI calls, validation, and serialization run outside the finalization transaction.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TravelDocumentProcessingResponse processClaimedDocument(Long documentId, UUID attemptId) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        TravelDocument document = travelDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document not found"));
        if (document.getProcessingStatus() != TravelDocumentProcessingStatus.PROCESSING
                || !attemptId.equals(document.getProcessingAttemptId())) {
            throw DocumentProcessingException.ownershipLost();
        }

        if (document.getContentType() == null
                || !List.of("application/pdf", "image/jpeg", "image/png").contains(document.getContentType())) {
            throw DocumentProcessingException.permanentInput("Only PDF, JPEG, and PNG documents are supported");
        }
        TravelDocumentType documentType = document.getDocumentType();
        String extractedData;
        try (InputStream inputStream = documentStorage.open(document.getStorageKey())) {
            DocumentAiInput aiInput = new DocumentAiInput(document.getOriginalFileName(),
                    document.getContentType(), inputStream.readAllBytes());
            if (aiInput.content().length == 0) {
                throw DocumentProcessingException.permanentInput("Stored document is empty and cannot be processed");
            }
            if (documentType == null) {
                documentType = toTravelDocumentType(documentClassifier.classify(aiInput));
            }
            if (documentType != TravelDocumentType.FLIGHT_CONFIRMATION) {
                throw new IllegalStateException("Extraction is not implemented for this document type");
            }

            FlightExtractionResult result = flightDocumentExtractor.extract(aiInput);
            flightExtractionValidator.validate(result);
            extractedData = jsonMapper.writeValueAsString(result);
        } catch (IOException exception) {
            // Preserve the cause for classification by the worker execution layer.
            throw new IllegalStateException("Failed to read stored document", exception);
        }
        // Close the input before committing success, so a close failure cannot follow a successful commit.
        return finalizationService.finalizeFlightDocument(documentId, attemptId, documentType, extractedData);
    }

    public List<TravelDocumentSummaryResponse> getDocuments(Long tripId) {
        return travelDocumentRepository.findAllByTrip_IdOrderByUploadedAtDescIdDesc(tripId).stream()
                .map(document -> new TravelDocumentSummaryResponse(
                        document.getId(), document.getOriginalFileName(), document.getContentType(),
                        document.getProcessingStatus(), document.getDocumentType(), document.getUploadedAt()))
                .toList();
    }

    public List<TravelDocumentExtractionSummaryResponse> getDocumentExtractions(Long tripId, Long documentId) {
        travelDocumentRepository.findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document not found"));
        return travelDocumentExtractionRepository
                .findAllByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId).stream()
                .map(extraction -> new TravelDocumentExtractionSummaryResponse(
                        extraction.getId(), extraction.getCreatedAt()))
                .toList();
    }

    public TravelDocumentExtractionResponse getCurrentDocumentExtraction(Long tripId, Long documentId) {
        TravelDocument document = travelDocumentRepository.findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document not found"));
        TravelDocumentExtraction extraction = travelDocumentExtractionRepository
                .findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document extraction not found"));
        return extractionResponse(document, extraction);
    }

    public TravelDocumentExtractionResponse getDocumentExtraction(
            Long tripId,
            Long documentId,
            Long extractionId
    ) {
        TravelDocument document = travelDocumentRepository
                .findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Travel document not found"
                        )
                );

        TravelDocumentExtraction extraction = travelDocumentExtractionRepository
                .findByIdAndTravelDocument_Id(extractionId, documentId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Travel document extraction not found"
                        )
                );

        return extractionResponse(document, extraction);
    }

    private TravelDocumentExtractionResponse extractionResponse(
            TravelDocument document, TravelDocumentExtraction extraction
    ) {
        JsonNode extractedData;
        try {
            extractedData = jsonMapper.readTree(extraction.getExtractedData());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to parse stored extraction JSON", exception);
        }
        if (extractedData == null || extractedData.isMissingNode() || extractedData.isNull()) {
            throw new IllegalStateException("Stored extraction JSON is empty");
        }

        return new TravelDocumentExtractionResponse(
                extraction.getId(),
                document.getId(),
                document.getDocumentType(),
                extraction.getCreatedAt(),
                extractedData
        );
    }

    private TravelDocumentType toTravelDocumentType(
            DocumentClassification classification
    ) {
        return switch (classification) {
            case FLIGHT_CONFIRMATION ->
                    TravelDocumentType.FLIGHT_CONFIRMATION;
            case HOTEL_BOOKING ->
                    TravelDocumentType.HOTEL_BOOKING;
            case TRAIN_TICKET ->
                    TRAIN_TICKET;
            case ATTRACTION_TICKET ->
                    TravelDocumentType.ATTRACTION_TICKET;
            case TOUR_BOOKING ->
                    TravelDocumentType.TOUR_BOOKING;
            case RESTAURANT_RESERVATION ->
                    TravelDocumentType.RESTAURANT_RESERVATION;
            case UNSUPPORTED ->
                    throw new IllegalArgumentException(
                            "Unsupported classification cannot be persisted as a document type"
                    );
        };
    }

}
