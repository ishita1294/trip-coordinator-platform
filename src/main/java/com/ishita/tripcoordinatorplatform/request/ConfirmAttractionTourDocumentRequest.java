package com.ishita.tripcoordinatorplatform.request;

import java.util.List;
import java.time.LocalDateTime;

/** Unchanged legacy reviews remain compatible; changed facts require an explicitly accepted source assessment. */
public record ConfirmAttractionTourDocumentRequest(List<Long> participantIds,Long extractionId,List<ArrivalReview> arrivalReviews,List<EndingReview> endingReviews, CorrectionAcceptance correctionAcceptance,List<EndingReview> endpointClarifications,List<Integer> selectedBookingIndexes,String acceptedStateToken) {
    public ConfirmAttractionTourDocumentRequest(List<Long> participants,Long extraction,List<ArrivalReview> arrivals,List<EndingReview> endings,CorrectionAcceptance acceptance,List<EndingReview> clarifications){this(participants,extraction,arrivals,endings,acceptance,clarifications,null,null);}
    public ConfirmAttractionTourDocumentRequest(List<Long> participants,Long extraction,List<ArrivalReview> arrivals,List<EndingReview> endings,CorrectionAcceptance acceptance){this(participants,extraction,arrivals,endings,acceptance,List.of());}
    public record CorrectionAcceptance(Long reviewId, long version) {}
    public ConfirmAttractionTourDocumentRequest(List<Long> participants,Long extraction,List<ArrivalReview> arrivals,List<EndingReview> endings){this(participants,extraction,arrivals,endings,null);}
    public ConfirmAttractionTourDocumentRequest(List<Long> participants,Long extraction,List<ArrivalReview> arrivals){this(participants,extraction,arrivals,List.of());}
    public ConfirmAttractionTourDocumentRequest(List<Long> participantIds){this(participantIds,null,List.of(),List.of());}
    public record EndingReview(Integer bookingIndex,String choice,com.ishita.tripcoordinatorplatform.model.Location location) {}
    public record ArrivalReview(Integer bookingIndex,LocalDateTime requiredArrivalLocal,String timeZone) {}
}
