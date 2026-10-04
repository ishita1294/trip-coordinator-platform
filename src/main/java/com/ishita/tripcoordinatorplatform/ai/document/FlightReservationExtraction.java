package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;

public record FlightReservationExtraction(
        String confirmationNumber,
        ConfirmationNumberType confirmationNumberType,
        List<FlightSegmentExtraction> segments
) {
}
