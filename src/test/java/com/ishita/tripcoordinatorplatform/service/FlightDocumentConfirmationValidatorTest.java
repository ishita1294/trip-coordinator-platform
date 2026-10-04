package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.ReservationConfirmationType;
import com.ishita.tripcoordinatorplatform.request.ConfirmFlightDocumentRequest;
import com.ishita.tripcoordinatorplatform.request.ReviewedFlightReservation;
import com.ishita.tripcoordinatorplatform.request.ReviewedFlightSegment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FlightDocumentConfirmationValidatorTest {

    private final FlightDocumentConfirmationValidator validator = new FlightDocumentConfirmationValidator();

    @Test
    void rejectsReservationWhenLatestArrivalDateIsBeforeToday() {
        LocalDate today = LocalDate.now();
        ConfirmFlightDocumentRequest request = request(
                segment(1, today.minusDays(2)),
                segment(2, today.minusDays(1)));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> validator.validate(request));
        assertEquals("Historical flight reservations cannot be confirmed", exception.getMessage());
    }

    @Test
    void allowsReservationWhenLatestArrivalDateIsToday() {
        LocalDate today = LocalDate.now();
        ConfirmFlightDocumentRequest request = request(segment(1, today));

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void allowsReservationWhenLatestArrivalDateIsInTheFuture() {
        LocalDate today = LocalDate.now();
        ConfirmFlightDocumentRequest request = request(segment(1, today.plusDays(1)));

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void allowsPastSegmentWhenFinalSegmentArrivesInTheFuture() {
        LocalDate today = LocalDate.now();
        ConfirmFlightDocumentRequest request = request(
                segment(1, today.minusDays(1)),
                segment(2, today.plusDays(1)));

        assertDoesNotThrow(() -> validator.validate(request));
    }

    private ConfirmFlightDocumentRequest request(ReviewedFlightSegment... segments) {
        return new ConfirmFlightDocumentRequest(List.of(new ReviewedFlightReservation(
                "ABC123", ReservationConfirmationType.PNR, List.of(segments))));
    }

    private ReviewedFlightSegment segment(int order, LocalDate arrivalDate) {
        return new ReviewedFlightSegment(
                order, "BA" + (200 + order), "LHR", arrivalDate, LocalTime.of(10, 0),
                "BOS", arrivalDate, LocalTime.of(12, 0));
    }
}
