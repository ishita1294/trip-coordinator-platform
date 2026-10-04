package com.ishita.tripcoordinatorplatform.request;

import java.time.LocalDate;
import java.time.LocalTime;

public record ReviewedFlightSegment(Integer segmentOrder,
                                    String flightNumber,
                                    String departureAirportCode,
                                    LocalDate departureDate,
                                    LocalTime departureTime,
                                    String arrivalAirportCode,
                                    LocalDate arrivalDate,
                                    LocalTime arrivalTime) {
}
