package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.request.ConfirmAttractionTourDocumentRequest;
import com.ishita.tripcoordinatorplatform.response.ConfirmDocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentConfirmationService {
    private final TravelDocumentRepository documents;
    private final FlightDocumentConfirmationService flights;
    private final AttractionTourDocumentConfirmationService attractionTours;

    public DocumentConfirmationService(TravelDocumentRepository documents,
            FlightDocumentConfirmationService flights, AttractionTourDocumentConfirmationService attractionTours) {
        this.documents = documents;
        this.flights = flights;
        this.attractionTours = attractionTours;
    }

    public ConfirmDocumentResponse confirm(Long tripId, Long documentId, ConfirmAttractionTourDocumentRequest request) {
        // Scalar lookup avoids caching document state before the concrete service acquires its lock.
        var documentType = documents.findDocumentTypeForDispatch(documentId, tripId)
                .orElseThrow(() -> {
                    // A null classification and a missing document both yield an empty scalar result.
                    if (!documents.existsByIdAndTrip_Id(documentId, tripId)) {
                        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document not found");
                    }
                    return new IllegalArgumentException("Travel document is not ready for confirmation");
                });
        return switch (documentType) {
            case FLIGHT_CONFIRMATION -> new ConfirmDocumentResponse(flights.confirm(tripId, documentId).reservationIds());
            case ATTRACTION_TICKET, TOUR_BOOKING -> attractionTours.confirm(tripId, documentId,
                    request == null ? null : request.participantIds());
            default -> throw new IllegalArgumentException("Confirmation is not implemented for this document type");
        };
    }
}
