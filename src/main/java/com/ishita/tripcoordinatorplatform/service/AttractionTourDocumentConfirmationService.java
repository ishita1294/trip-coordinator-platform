package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourExtractionResult;
import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourExtractionValidator;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.ReservationRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.request.CreateItineraryItemRequest;
import com.ishita.tripcoordinatorplatform.response.ConfirmDocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AttractionTourDocumentConfirmationService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservations;
    private final ItineraryItemService itineraryItems;
    private final AttractionTourExtractionValidator validator;
    private final JsonMapper jsonMapper;

    public AttractionTourDocumentConfirmationService(TravelDocumentRepository documents,
            TravelDocumentExtractionRepository extractions, ReservationRepository reservationRepository,
            ReservationService reservations, ItineraryItemService itineraryItems,
            AttractionTourExtractionValidator validator, JsonMapper jsonMapper) {
        this.documents = documents;
        this.extractions = extractions;
        this.reservationRepository = reservationRepository;
        this.reservations = reservations;
        this.itineraryItems = itineraryItems;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public ConfirmDocumentResponse confirm(Long tripId, Long documentId, List<Long> participantIds) {
        // Hold the document lock through all writes, including participant/conflict creation.
        // Concurrent confirmation or reprocess cannot create a second set of commitments.
        var document = documents.findForConfirmation(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document not found"));
        if (document.getDocumentType() != TravelDocumentType.ATTRACTION_TICKET
                && document.getDocumentType() != TravelDocumentType.TOUR_BOOKING) {
            throw new IllegalArgumentException("Travel document must be an attraction ticket or tour booking");
        }
        if (document.getProcessingStatus() == TravelDocumentProcessingStatus.PROCESSED) {
            throw new IllegalArgumentException("Travel document has already been confirmed");
        }
        if (document.getProcessingStatus() != TravelDocumentProcessingStatus.REVIEW_REQUIRED) {
            throw new IllegalArgumentException("Travel document is not ready for confirmation");
        }
        var extraction = extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document extraction not found"));
        AttractionTourExtractionResult proposal;
        try {
            proposal = jsonMapper.readValue(extraction.getExtractedData(), AttractionTourExtractionResult.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to parse stored attraction/tour extraction", exception);
        }
        validator.validateForConfirmation(proposal);

        Set<String> references = new HashSet<>();
        for (var booking : proposal.bookings()) {
            if (booking.bookingReference() != null && !booking.bookingReference().isBlank()
                    && (!references.add(booking.bookingReference())
                    || reservationRepository.existsByTrip_IdAndConfirmationNumber(tripId, booking.bookingReference()))) {
                throw new IllegalArgumentException("Reservation with this confirmation number already exists");
            }
        }

        List<Long> reservationIds = new ArrayList<>();
        for (var booking : proposal.bookings()) {
            LocalDateTime start = LocalDateTime.of(booking.startDate(), booking.startTime());
            LocalDateTime end = booking.endDate() != null && booking.endTime() != null
                    ? LocalDateTime.of(booking.endDate(), booking.endTime())
                    : booking.durationMinutes() != null ? start.plusMinutes(booking.durationMinutes()) : null;
            Reservation reservation = new Reservation();
            reservation.setType(ReservationType.ACTIVITY);
            reservation.setName(booking.name());
            reservation.setStartDateTime(start);
            reservation.setEndDateTime(end);
            reservation.setProvider(booking.provider());
            reservation.setStructuredLocation(booking.meetingPoint());
            if (booking.bookingReference() != null && !booking.bookingReference().isBlank()) {
                reservation.setConfirmationNumber(booking.bookingReference());
                reservation.setConfirmationNumberType(ReservationConfirmationType.BOOKING_ID);
            }
            Reservation saved = reservations.createReservation(tripId, reservation);
            reservationIds.add(saved.getId());

            CreateItineraryItemRequest item = new CreateItineraryItemRequest();
            item.setReservationId(saved.getId());
            item.setTitle(booking.name());
            item.setStartDateTime(start);
            item.setEndDateTime(end);
            item.setFlexibility(end == null ? ItineraryItemFlexibility.TIME_CONSTRAINED : ItineraryItemFlexibility.FIXED);
            item.setParticipantIds(participantIds);
            // Reuses SOLO owner assignment, GROUP validation and participant overlap detection.
            itineraryItems.createItineraryItem(tripId, item);
        }
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);
        documents.save(document);
        return new ConfirmDocumentResponse(List.copyOf(reservationIds));
    }
}
