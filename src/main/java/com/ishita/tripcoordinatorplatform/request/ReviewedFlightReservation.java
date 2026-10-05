package com.ishita.tripcoordinatorplatform.request;

import com.ishita.tripcoordinatorplatform.model.ReservationConfirmationType;

import java.util.List;

// Internal confirmation values copied directly from the immutable AI extraction.
public record ReviewedFlightReservation(String confirmationNumber,
                                        ReservationConfirmationType confirmationNumberType,
                                        List<ReviewedFlightSegment> segments) {
}
