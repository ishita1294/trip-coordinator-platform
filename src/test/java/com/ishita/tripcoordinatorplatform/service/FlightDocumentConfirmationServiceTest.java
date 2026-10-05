package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.ai.document.*;
import tools.jackson.databind.json.JsonMapper;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import com.ishita.tripcoordinatorplatform.controller.TravelDocumentController;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void createsTwoReservationsWithTheirOwnSegments() {
        storeProposal(
                group("ABC123", segment(1, "FCO", "LHR")),
                new ReviewedFlightReservation("DEF456", ReservationConfirmationType.BOOKING_ID,
                        List.of(segment(1, "LHR", "BOS"))));
        String originalJson = extraction.getExtractedData();
        var response = service.confirm(1L, 2L);

        assertEquals(List.of(1L, 2L), response.reservationIds());
        verify(reservations, times(2)).save(any(Reservation.class));
        ArgumentCaptor<FlightSegment> captured = ArgumentCaptor.forClass(FlightSegment.class);
        verify(segments, times(2)).save(captured.capture());
        assertEquals(List.of(1L, 2L), captured.getAllValues().stream()
                .map(segment -> segment.getReservation().getId()).toList());
        assertEquals(TravelDocumentProcessingStatus.PROCESSED, document.getProcessingStatus());
        assertEquals(originalJson, extraction.getExtractedData());
        ArgumentCaptor<Reservation> bookings = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations, times(2)).save(bookings.capture());
        assertEquals(List.of("ABC123", "DEF456"), bookings.getAllValues().stream()
                .map(Reservation::getConfirmationNumber).toList());
        assertEquals(ReservationConfirmationType.BOOKING_ID, bookings.getAllValues().getLast().getConfirmationNumberType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirmationNumber", "confirmationNumberType", "segmentOrder", "flightNumber",
            "departureAirportCode", "departureDate", "departureTime",
            "arrivalAirportCode", "arrivalDate", "arrivalTime"})
    void missingRequiredExtractionFieldRejectsBeforeAnyPersistence(String field) {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")));
        var tree = jsonMapper.readTree(extraction.getExtractedData());
        ObjectNode reservation = (ObjectNode) tree.path("reservations").get(0);
        ObjectNode target = field.startsWith("confirmation")
                ? reservation : (ObjectNode) reservation.path("segments").get(0);
        target.putNull(field);
        extraction.setExtractedData(jsonMapper.writeValueAsString(tree));
        assertIncomplete();
    }

    @Test
    void omittedRequiredFieldIsAlsoIncomplete() {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")));
        var tree = jsonMapper.readTree(extraction.getExtractedData());
        ((ObjectNode) tree.path("reservations").get(0).path("segments").get(0)).remove("departureDate");
        extraction.setExtractedData(jsonMapper.writeValueAsString(tree));
        assertIncomplete();
    }

    @Test
    void incompleteLaterReservationPreventsAllPersistence() {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")),
                group("DEF456", new ReviewedFlightSegment(1, "BA215", "LHR",
                        LocalDate.now().plusDays(1), null, "BOS",
                        LocalDate.now().plusDays(1), LocalTime.NOON)));
        assertIncomplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reservations\":[]}",
            "{\"reservations\":[null]}",
            "{\"reservations\":[{\"confirmationNumber\":\"ABC123\",\"confirmationNumberType\":\"PNR\",\"segments\":[]}]}",
            "{\"segments\":[]}"})
    void incompleteStructureCannotConfirm(String json) {
        extraction.setExtractedData(json);
        assertIncomplete();
    }

    @Test
    void malformedStoredJsonRemainsAnInternalDataError() {
        extraction.setExtractedData("invalid JSON");
        assertThrows(IllegalStateException.class, () -> service.confirm(1L, 2L));
        verifyNoWrites();
    }

    @Test
    void rejectsAlreadyProcessedDocument() {
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);
        var error = assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals("Travel document has already been confirmed", error.getMessage());
        verifyNoWrites();
        verifyNoInteractions(extractions);
    }

    @Test
    void requiresReviewRequiredStatus() {
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSING);
        assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals(TravelDocumentProcessingStatus.PROCESSING, document.getProcessingStatus());
        verifyNoWrites();
        verifyNoInteractions(extractions);
    }

    @Test
    void verifiesDocumentTripOwnershipBeforeExtractionLookup() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.confirm(99L, 2L));
        verifyNoInteractions(extractions);
        verifyNoWrites();
    }

    @Test
    void currentExtractionMustExist() {
        when(extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(2L))
                .thenReturn(Optional.empty());
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.confirm(1L, 2L));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoWrites();
    }

    @Test
    void rejectsDuplicateInLaterGroupBeforeAnyPersistence() {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")),
                group("DEF456", segment(1, "LHR", "BOS")));
        when(reservations.existsByTrip_IdAndConfirmationNumber(1L, "DEF456")).thenReturn(true);
        var error = assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals("Reservation with this confirmation number already exists", error.getMessage());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, document.getProcessingStatus());
        verifyNoWrites();
    }

    @Test
    void rejectsDuplicateIdentifiersWithinExtraction() {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")),
                group("ABC123", segment(1, "LHR", "BOS")));
        assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        verifyNoWrites();
    }

    @Test
    void historicalValidationIsPreserved() {
        LocalDate past = LocalDate.now().minusDays(2);
        storeProposal(group("OLD123", new ReviewedFlightSegment(
                1, "BA283", "FCO", past, LocalTime.of(10, 0), "LHR", past, LocalTime.NOON)));
        var error = assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals("Historical flight reservations cannot be confirmed", error.getMessage());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, document.getProcessingStatus());
        verifyNoWrites();
    }

    @Test
    void finalAirportAndOrderValidationIsPreserved() {
        storeProposal(group("ABC123", segment(1, "FCO", "INVALID")));
        var error = assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals("Arrival airport code must be 3 letters", error.getMessage());
        storeProposal(group("ABC123", segment(1, "FCO", "LHR"), segment(1, "LHR", "BOS")));
        error = assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals("Duplicate flight segment order within reservation", error.getMessage());
        verifyNoWrites();
    }

    @Test
    void bodylessEndpointConfirmsCurrentExtraction() throws Exception {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")));
        var mvc = MockMvcBuilders.standaloneSetup(new TravelDocumentController(
                mock(TravelDocumentUploadService.class), mock(TravelDocumentProcessingService.class), service)).build();
        mvc.perform(post("/trips/1/documents/2/confirm")).andExpect(status().isOk());
        assertEquals(TravelDocumentProcessingStatus.PROCESSED, document.getProcessingStatus());
    }

    @Test
    void clientFlightValuesCannotChangePersistedExtraction() throws Exception {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")));
        String originalJson = extraction.getExtractedData();
        var mvc = MockMvcBuilders.standaloneSetup(new TravelDocumentController(
                mock(TravelDocumentUploadService.class), mock(TravelDocumentProcessingService.class), service)).build();
        mvc.perform(post("/trips/1/documents/2/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reservations":[{"confirmationNumber":"OVERRIDE","segments":[
                          {"flightNumber":"LH401","departureAirportCode":"XXX","departureDate":"2099-01-01"}
                        ]}]}
                        """)).andExpect(status().isOk());
        ArgumentCaptor<Reservation> booking = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(booking.capture());
        assertEquals("ABC123", booking.getValue().getConfirmationNumber());
        ArgumentCaptor<FlightSegment> leg = ArgumentCaptor.forClass(FlightSegment.class);
        verify(segments).save(leg.capture());
        assertEquals("BA1", leg.getValue().getFlightNumber());
        assertEquals("FCO", leg.getValue().getDepartureAirportCode());
        assertEquals(LocalDate.now().plusDays(1), leg.getValue().getDepartureDateTime().toLocalDate());
        assertEquals(originalJson, extraction.getExtractedData());
    }

    @Test
    void persistenceFailureRollsBackConfirmationTransaction() {
        storeProposal(group("ABC123", segment(1, "FCO", "LHR")));
        when(segments.save(any(FlightSegment.class))).thenThrow(new IllegalStateException("Persistence failed"));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var transactionalService = (FlightDocumentConfirmationService) proxy.getProxy();
        assertThrows(IllegalStateException.class, () -> transactionalService.confirm(1L, 2L));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        verify(documents, never()).save(any());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, document.getProcessingStatus());
        verify(extractions, never()).save(any());
    }

    private void assertIncomplete() {
        String originalJson = extraction.getExtractedData();
        var error = assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L));
        assertEquals("Flight extraction is incomplete. Reprocess the document or add the flight manually.",
                error.getMessage());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, document.getProcessingStatus());
        assertEquals(originalJson, extraction.getExtractedData());
        verifyNoWrites();
        verify(reservations, never()).existsByTrip_IdAndConfirmationNumber(anyLong(), any());
    }

    private void verifyNoWrites() {
        verify(reservations, never()).save(any());
        verify(segments, never()).save(any());
        verify(documents, never()).save(any());
        verify(extractions, never()).save(any());
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
