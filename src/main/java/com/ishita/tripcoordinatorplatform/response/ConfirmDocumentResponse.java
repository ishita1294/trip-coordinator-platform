package com.ishita.tripcoordinatorplatform.response;

import java.util.List;

public record ConfirmDocumentResponse(List<Long> reservationIds,List<Integer> confirmedBookingIndexes,com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus documentStatus) {
    public ConfirmDocumentResponse(List<Long> reservationIds){this(reservationIds,List.of(),null);}
}
