package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.Trip;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.Instant;
import java.util.Set;


@Service
public class TravelDocumentUploadService {


    // Only physical file formats supported by the document-processing pipeline
    // are accepted at upload time.
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private final TripRepository tripRepository;
    private final TravelDocumentRepository travelDocumentRepository;
    private final DocumentStorage documentStorage;

    public TravelDocumentUploadService(
            TripRepository tripRepository,
            TravelDocumentRepository travelDocumentRepository,
            DocumentStorage documentStorage
    ) {
        this.tripRepository = tripRepository;
        this.travelDocumentRepository = travelDocumentRepository;
        this.documentStorage = documentStorage;
    }

    public TravelDocument uploadDocument(
            Long tripId,
            String originalFileName,
            String contentType,
            long contentLength,
            InputStream inputStream
    ) {
        // A document must always belong to an existing trip.
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Trip not found"
                        )
                );

        // Reject unsupported physical formats before storing the file.
        if (contentType == null
                || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only PDF, JPEG, and PNG documents are supported"
            );
        }

        // Store the actual file separately from its database metadata.
        String storageKey = documentStorage.store(
                tripId,
                originalFileName,
                contentType,
                contentLength,
                inputStream
        );

        TravelDocument document = new TravelDocument();
        document.setTrip(trip);
        document.setOriginalFileName(originalFileName);
        document.setStorageKey(storageKey);
        document.setContentType(contentType);

        // Upload only records the document. AI processing has not started yet.
        document.setProcessingStatus(
                TravelDocumentProcessingStatus.UPLOADED
        );

        // documentType intentionally remains null until AI classification succeeds.
        document.setUploadedAt(Instant.now());

        return travelDocumentRepository.save(document);
    }

}
