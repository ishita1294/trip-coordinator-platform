package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;

// Represents flight-booking information proposed by AI.
// It is not trusted or persisted as a Reservation until backend validation
// and user confirmation are completed.
public record FlightExtractionResult(List<FlightReservationExtraction> reservations) {
}
