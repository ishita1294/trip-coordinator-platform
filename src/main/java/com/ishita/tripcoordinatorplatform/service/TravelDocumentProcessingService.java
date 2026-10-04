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
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

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

    public TravelDocumentProcessingService(
            TravelDocumentRepository travelDocumentRepository,
            DocumentClassifier documentClassifier,
            DocumentStorage documentStorage,
            FlightDocumentExtractor flightDocumentExtractor,
            FlightExtractionValidator flightExtractionValidator,
            TravelDocumentExtractionRepository travelDocumentExtractionRepository,
            JsonMapper jsonMapper
    ) {
        this.travelDocumentRepository = travelDocumentRepository;
        this.documentClassifier = documentClassifier;
        this.documentStorage = documentStorage;
        this.flightDocumentExtractor = flightDocumentExtractor;
        this.flightExtractionValidator = flightExtractionValidator;
        this.travelDocumentExtractionRepository = travelDocumentExtractionRepository;
        this.jsonMapper = jsonMapper;
    }

    public TravelDocument processDocument(
            Long tripId,
            Long documentId
    ) {
        TravelDocument document = travelDocumentRepository
                .findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Travel document not found"
                        )
                );

        document.setProcessingStatus(
                TravelDocumentProcessingStatus.PROCESSING
        );
        travelDocumentRepository.save(document);


        try (
                // Open the original file using the storage key saved at upload time.
                InputStream inputStream =
                        documentStorage.open(document.getStorageKey())
        ) {
            // Build the AI input from the actual stored document, not pre-extracted text.
            DocumentAiInput aiInput = new DocumentAiInput(
                    document.getOriginalFileName(),
                    document.getContentType(),
                    inputStream.readAllBytes()
            );

            DocumentClassification classification =
                    documentClassifier.classify(aiInput);

            if (classification == DocumentClassification.UNSUPPORTED) {
                document.setProcessingStatus(
                        TravelDocumentProcessingStatus.FAILED
                );

                return travelDocumentRepository.save(document);
            }

            document.setDocumentType(
                    toTravelDocumentType(classification)
            );

            if (document.getDocumentType() == TravelDocumentType.FLIGHT_CONFIRMATION) {
                extractFlightDocument(document, aiInput);
            }

            // Keep PROCESSING because the extraction still requires user review.
            return travelDocumentRepository.save(document);

        } catch (IOException exception) {
            // A document that cannot be read cannot continue through processing.
            document.setProcessingStatus(
                    TravelDocumentProcessingStatus.FAILED
            );
            travelDocumentRepository.save(document);

            throw new IllegalStateException(
                    "Failed to read stored document",
                    exception
            );

        } catch (RuntimeException exception) {
            // Preserve FAILED even when the AI provider or validation step fails.
            document.setProcessingStatus(
                    TravelDocumentProcessingStatus.FAILED
            );
            travelDocumentRepository.save(document);

            throw exception;
        }
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

    private void extractFlightDocument(TravelDocument document, DocumentAiInput aiInput) {
        FlightExtractionResult result = flightDocumentExtractor.extract(aiInput);
        flightExtractionValidator.validate(result);
        String extractedData = jsonMapper.writeValueAsString(result);

        TravelDocumentExtraction extraction = new TravelDocumentExtraction();
        extraction.setTravelDocument(document);
        extraction.setExtractedData(extractedData);
        extraction.setCreatedAt(Instant.now());
        travelDocumentExtractionRepository.save(extraction);
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
