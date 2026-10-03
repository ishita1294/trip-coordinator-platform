package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.LocalDateTime;

// Represents one flight leg proposed from the uploaded document.
// Time zones are intentionally excluded because they must come from
// verified airport data rather than AI inference.
public record FlightSegmentExtraction(Integer segmentOrder,
                                      String flightNumber,
                                      String departureAirportCode,
                                      LocalDateTime departureDateTime,
                                      String arrivalAirportCode,
                                      LocalDateTime arrivalDateTime) {
}
