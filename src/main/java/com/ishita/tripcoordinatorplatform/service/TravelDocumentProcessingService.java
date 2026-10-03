package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassification;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;

import static com.ishita.tripcoordinatorplatform.model.TravelDocumentType.TRAIN_TICKET;

@Service
public class TravelDocumentProcessingService {

    private final TravelDocumentRepository travelDocumentRepository;
    private final DocumentClassificationService documentClassificationService;
    private final DocumentStorage documentStorage;

    public TravelDocumentProcessingService(
            TravelDocumentRepository travelDocumentRepository,
            DocumentClassificationService documentClassificationService,
            DocumentStorage documentStorage
    ) {
        this.travelDocumentRepository = travelDocumentRepository;
        this.documentClassificationService = documentClassificationService;
        this.documentStorage = documentStorage;
    }

    public TravelDocument classifyDocument(
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
                    documentClassificationService.classify(aiInput);

            if (classification == DocumentClassification.UNSUPPORTED) {
                document.setProcessingStatus(
                        TravelDocumentProcessingStatus.FAILED
                );

                return travelDocumentRepository.save(document);
            }

            document.setDocumentType(
                    toTravelDocumentType(classification)
            );

            // Classification succeeded, but extraction still needs to run.
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
