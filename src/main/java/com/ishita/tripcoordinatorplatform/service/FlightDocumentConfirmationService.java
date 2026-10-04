package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.FlightSegment;
import com.ishita.tripcoordinatorplatform.model.Reservation;
import com.ishita.tripcoordinatorplatform.model.ReservationType;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.ReservationRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.request.ConfirmFlightDocumentRequest;
import com.ishita.tripcoordinatorplatform.request.ReviewedFlightReservation;
import com.ishita.tripcoordinatorplatform.request.ReviewedFlightSegment;
import com.ishita.tripcoordinatorplatform.response.ConfirmFlightDocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class FlightDocumentConfirmationService {

    private final TravelDocumentRepository documentRepository;
    private final TravelDocumentExtractionRepository extractionRepository;
    private final ReservationRepository reservationRepository;
    private final FlightDocumentConfirmationValidator validator;
    private final ReservationService reservationService;
    private final FlightSegmentService flightSegmentService;

    public FlightDocumentConfirmationService(
            TravelDocumentRepository documentRepository,
            TravelDocumentExtractionRepository extractionRepository,
            ReservationRepository reservationRepository,
            FlightDocumentConfirmationValidator validator,
            ReservationService reservationService,
            FlightSegmentService flightSegmentService
    ) {
        this.documentRepository = documentRepository;
        this.extractionRepository = extractionRepository;
        this.reservationRepository = reservationRepository;
        this.validator = validator;
        this.reservationService = reservationService;
        this.flightSegmentService = flightSegmentService;
    }

    public ConfirmFlightDocumentResponse confirm(
            Long tripId, Long documentId, Long extractionId, ConfirmFlightDocumentRequest request
    ) {
        TravelDocument document = documentRepository.findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document not found"));
        extractionRepository.findByIdAndTravelDocument_Id(extractionId, documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document extraction not found"));
        if (document.getDocumentType() != TravelDocumentType.FLIGHT_CONFIRMATION) {
            throw new IllegalArgumentException("Travel document must be a flight confirmation");
        }
        if (document.getProcessingStatus() == TravelDocumentProcessingStatus.PROCESSED) {
            throw new IllegalArgumentException("Travel document has already been confirmed");
        }
        validator.validate(request);

        // Check every group before writing; existing reservations use number-only uniqueness.
        Set<String> confirmationNumbers = new HashSet<>();
        for (ReviewedFlightReservation reviewed : request.reservations()) {
            if (!confirmationNumbers.add(reviewed.confirmationNumber())
                    || reservationRepository.existsByTrip_IdAndConfirmationNumber(
                            tripId, reviewed.confirmationNumber())) {
                throw new IllegalArgumentException("Reservation with this confirmation number already exists");
            }
        }

        List<Long> reservationIds = new ArrayList<>();
        for (ReviewedFlightReservation reviewed : request.reservations()) {
            List<ReviewedFlightSegment> segments = reviewed.segments().stream()
                    .sorted(Comparator.comparing(ReviewedFlightSegment::segmentOrder))
                    .toList();
            ReviewedFlightSegment first = segments.getFirst();
            ReviewedFlightSegment last = segments.getLast();
            Reservation reservation = new Reservation();
            reservation.setTrip(document.getTrip());
            reservation.setType(ReservationType.FLIGHT);
            reservation.setConfirmationNumber(reviewed.confirmationNumber());
            reservation.setConfirmationNumberType(reviewed.confirmationNumberType());
            reservation.setStartDateTime(LocalDateTime.of(first.departureDate(), first.departureTime()));
            reservation.setEndDateTime(LocalDateTime.of(last.arrivalDate(), last.arrivalTime()));
            reservation.setName("Flight " + first.departureAirportCode() + " → " + last.arrivalAirportCode());
            Reservation saved = reservationService.createReservation(tripId, reservation);
            reservationIds.add(saved.getId());

            for (ReviewedFlightSegment reviewedSegment : segments) {
                FlightSegment segment = new FlightSegment();
                segment.setReservation(saved);
                segment.setSegmentOrder(reviewedSegment.segmentOrder());
                segment.setFlightNumber(reviewedSegment.flightNumber());
                segment.setDepartureAirportCode(reviewedSegment.departureAirportCode());
                segment.setDepartureDateTime(LocalDateTime.of(
                        reviewedSegment.departureDate(), reviewedSegment.departureTime()));
                segment.setArrivalAirportCode(reviewedSegment.arrivalAirportCode());
                segment.setArrivalDateTime(LocalDateTime.of(
                        reviewedSegment.arrivalDate(), reviewedSegment.arrivalTime()));
                flightSegmentService.createFlightSegment(tripId, saved.getId(), segment);
            }
        }

        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);
        documentRepository.save(document);
        return new ConfirmFlightDocumentResponse(List.copyOf(reservationIds));
    }
}
