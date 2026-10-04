package com.ishita.tripcoordinatorplatform.request;

import java.util.List;

public record ConfirmFlightDocumentRequest(List<ReviewedFlightReservation> reservations) {
}
