package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.ai.document.*;
import tools.jackson.databind.json.JsonMapper;
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
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final TravelDocumentExtraction extraction = new TravelDocumentExtraction();
    private FlightDocumentConfirmationService service;
    private long nextReservationId;

    @BeforeEach
    void setUp() {
        Trip trip = new Trip();
        trip.setId(1L);
        document.setTrip(trip);
        document.setDocumentType(TravelDocumentType.FLIGHT_CONFIRMATION);
        document.setProcessingStatus(TravelDocumentProcessingStatus.REVIEW_REQUIRED);
        when(documents.findByIdAndTrip_Id(2L, 1L)).thenReturn(Optional.of(document));
        when(extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(2L))
                .thenReturn(Optional.of(extraction));
        extraction.setId(3L);
        extraction.setExtractedData("{\"reservations\":[]}");
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
                new FlightSegmentService(segments, reservations), jsonMapper);
    }

    @Test
    void createsOneReservationAndAllSegmentsThenMarksDocumentProcessed() {
        ReviewedFlightSegment first = segment(1, "FCO", "LHR");
        ReviewedFlightSegment last = segment(2, "LHR", "BOS");
        storeProposal(group("ABC123", last, first));
        String originalJson = extraction.getExtractedData();
        var response = service.confirm(1L, 2L);

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
        assertEquals(originalJson, extraction.getExtractedData());
        verify(extractions).findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(2L);
        verify(extractions, never()).findByIdAndTravelDocument_Id(anyLong(), anyLong());
    }

    private void storeProposal(ReviewedFlightReservation... groups) {
        extraction.setExtractedData(jsonMapper.writeValueAsString(new FlightExtractionResult(
                java.util.Arrays.stream(groups).map(group -> new FlightReservationExtraction(
                        group.confirmationNumber(), group.confirmationNumberType() == null ? null
                        : ConfirmationNumberType.valueOf(group.confirmationNumberType().name()),
                        group.segments().stream().map(segment -> new FlightSegmentExtraction(
                                segment.segmentOrder(), segment.flightNumber(), segment.departureAirportCode(),
                                segment.departureDate(), segment.departureTime(), segment.arrivalAirportCode(),
                                segment.arrivalDate(), segment.arrivalTime())).toList())).toList())));
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
