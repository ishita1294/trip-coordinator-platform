package com.ishita.tripcoordinatorplatform.request;

import com.ishita.tripcoordinatorplatform.model.ReservationConfirmationType;

import java.util.List;

public record ReviewedFlightReservation(String confirmationNumber,
                                        ReservationConfirmationType confirmationNumberType,
                                        List<ReviewedFlightSegment> segments) {
}
