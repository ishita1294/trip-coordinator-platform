package com.ishita.tripcoordinatorplatform.request;

import java.time.LocalDate;
import java.time.LocalTime;

// Internal confirmation values copied directly from the immutable AI extraction.
public record ReviewedFlightSegment(Integer segmentOrder,
                                    String flightNumber,
                                    String departureAirportCode,
                                    LocalDate departureDate,
                                    LocalTime departureTime,
                                    String arrivalAirportCode,
                                    LocalDate arrivalDate,
                                    LocalTime arrivalTime) {
}
