package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class FlightDocumentConfirmationServiceTest {

    private final TravelDocumentRepository documents = mock(TravelDocumentRepository.class);
    private final TravelDocumentExtractionRepository extractions = mock(TravelDocumentExtractionRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final FlightSegmentRepository segments = mock(FlightSegmentRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TravelDocument document = new TravelDocument();
    private FlightDocumentConfirmationService service;
    private long nextReservationId;

    @BeforeEach
    void setUp() {
        Trip trip = new Trip();
        trip.setId(1L);
        document.setTrip(trip);
        document.setDocumentType(TravelDocumentType.FLIGHT_CONFIRMATION);
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSING);
        when(documents.findByIdAndTrip_Id(2L, 1L)).thenReturn(Optional.of(document));
        when(extractions.findByIdAndTravelDocument_Id(3L, 2L))
                .thenReturn(Optional.of(new TravelDocumentExtraction()));
        when(trips.findById(1L)).thenReturn(Optional.of(trip));
        when(reservations.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", ++nextReservationId);
            when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
            return reservation;
        });
        when(segments.save(any(FlightSegment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new FlightDocumentConfirmationService(documents, extractions, reservations,
                new FlightDocumentConfirmationValidator(),
                new ReservationService(reservations, trips),
                new FlightSegmentService(segments, reservations));
    }

    @Test
    void createsOneReservationAndAllSegmentsThenMarksDocumentProcessed() {
        ReviewedFlightSegment first = segment(1, "FCO", "LHR");
        ReviewedFlightSegment last = segment(2, "LHR", "BOS");
        var response = service.confirm(1L, 2L, 3L, request(group("ABC123", last, first)));

        assertEquals(List.of(1L), response.reservationIds());
        ArgumentCaptor<Reservation> reservation = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(reservation.capture());
        Reservation saved = reservation.getValue();
        assertAll(
                () -> assertSame(document.getTrip(), saved.getTrip()),
                () -> assertEquals(ReservationType.FLIGHT, saved.getType()),
                () -> assertEquals(ReservationStatus.PENDING, saved.getStatus()),
                () -> assertEquals("ABC123", saved.getConfirmationNumber()),
                () -> assertEquals(ReservationConfirmationType.PNR, saved.getConfirmationNumberType()),
                () -> assertEquals("Flight FCO → BOS", saved.getName()),
                () -> assertEquals(LocalDateTime.of(first.departureDate(), first.departureTime()), saved.getStartDateTime()),
                () -> assertEquals(LocalDateTime.of(last.arrivalDate(), last.arrivalTime()), saved.getEndDateTime()));
        ArgumentCaptor<FlightSegment> captured = ArgumentCaptor.forClass(FlightSegment.class);
        verify(segments, times(2)).save(captured.capture());
        List<FlightSegment> savedSegments = captured.getAllValues();
        for (int i = 0; i < savedSegments.size(); i++) {
            FlightSegment actual = savedSegments.get(i);
            ReviewedFlightSegment expected = List.of(first, last).get(i);
            assertSame(saved, actual.getReservation());
            assertEquals(expected.segmentOrder(), actual.getSegmentOrder());
            assertEquals(expected.flightNumber(), actual.getFlightNumber());
            assertEquals(expected.departureAirportCode(), actual.getDepartureAirportCode());
            assertEquals(expected.arrivalAirportCode(), actual.getArrivalAirportCode());
            assertEquals(LocalDateTime.of(expected.departureDate(), expected.departureTime()), actual.getDepartureDateTime());
            assertEquals(LocalDateTime.of(expected.arrivalDate(), expected.arrivalTime()), actual.getArrivalDateTime());
            assertNull(actual.getDepartureTimeZone());
        }
        assertEquals(TravelDocumentProcessingStatus.PROCESSED, document.getProcessingStatus());
        var order = inOrder(segments, documents);
        order.verify(segments, times(2)).save(any(FlightSegment.class));
        order.verify(documents).save(document);
        verify(extractions, never()).save(any());
    }

    @Test
    void createsTwoReservationsWithTheirOwnSegments() {
        var response = service.confirm(1L, 2L, 3L, request(
                group("ABC123", segment(1, "FCO", "LHR")),
                group("DEF456", segment(1, "LHR", "BOS"))));

        assertEquals(List.of(1L, 2L), response.reservationIds());
        verify(reservations, times(2)).save(any(Reservation.class));
        ArgumentCaptor<FlightSegment> captured = ArgumentCaptor.forClass(FlightSegment.class);
        verify(segments, times(2)).save(captured.capture());
        assertEquals(List.of(1L, 2L), captured.getAllValues().stream()
                .map(segment -> segment.getReservation().getId()).toList());
        assertEquals(TravelDocumentProcessingStatus.PROCESSED, document.getProcessingStatus());
    }

    @Test
    void rejectsAlreadyProcessedDocument() {
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(1L, 2L, 3L, request(group("ABC123", segment(1, "FCO", "LHR")))));
        assertEquals("Travel document has already been confirmed", error.getMessage());
        verifyNoWrites();
    }

    @Test
    void rejectsDuplicateInLaterGroupBeforeAnyPersistence() {
        when(reservations.existsByTrip_IdAndConfirmationNumber(1L, "DEF456")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L, 3L, request(
                group("ABC123", segment(1, "FCO", "LHR")),
                group("DEF456", segment(1, "LHR", "BOS")))));
        verifyNoWrites();
        assertEquals(TravelDocumentProcessingStatus.PROCESSING, document.getProcessingStatus());
    }

    @Test
    void validatorFailureDoesNotPersistAnything() {
        assertThrows(IllegalArgumentException.class,
                () -> service.confirm(1L, 2L, 3L, new ConfirmFlightDocumentRequest(List.of())));
        verifyNoWrites();
        verify(reservations, never()).existsByTrip_IdAndConfirmationNumber(anyLong(), any());
        assertEquals(TravelDocumentProcessingStatus.PROCESSING, document.getProcessingStatus());
    }

    private void verifyNoWrites() {
        verify(reservations, never()).save(any());
        verify(segments, never()).save(any());
        verify(documents, never()).save(any());
        verify(extractions, never()).save(any());
    }

    private ConfirmFlightDocumentRequest request(ReviewedFlightReservation... groups) {
        return new ConfirmFlightDocumentRequest(List.of(groups));
    }

    private ReviewedFlightReservation group(String pnr, ReviewedFlightSegment... legs) {
        return new ReviewedFlightReservation(pnr, ReservationConfirmationType.PNR, List.of(legs));
    }

    private ReviewedFlightSegment segment(int order, String departure, String arrival) {
        LocalDate date = LocalDate.now().plusDays(order);
        return new ReviewedFlightSegment(order, "BA" + order, departure, date, LocalTime.of(10, 0),
                arrival, date, LocalTime.of(12, 0));
    }
}
