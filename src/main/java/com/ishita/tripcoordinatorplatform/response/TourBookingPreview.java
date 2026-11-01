package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourBookingExtraction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Application-validated effective values, not AI evidence. Reconstructed from immutable source plus explicit corrections. */
public record TourBookingPreview(int schemaVersion,String contractIdentity,Long extractionId,List<Booking> bookings,
        boolean confirmationAllowed,String confirmationBlockCode,boolean schedulingValidated) {
    public TourBookingPreview(int version,String identity,Long extraction,List<Booking> bookings){this(version,identity,extraction,bookings,true,null,true);}
    public record Booking(int bookingIndex,AttractionTourBookingExtraction booking,LocalDateTime requiredArrivalLocal,
                          String endingLocationChoice,Set<String> invalidatedFields,boolean confirmationEligible,
                          com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingEligibility.Booking eligibility,
                          com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification lifecycleClarification) {
        public Booking(int index,AttractionTourBookingExtraction booking,LocalDateTime arrival,String ending,Set<String> invalidated,boolean eligible){
            this(index,booking,arrival,ending,invalidated,eligible,null,null);
        }
        public Booking(int index,AttractionTourBookingExtraction booking,LocalDateTime arrival,String ending,Set<String> invalidated){
            this(index,booking,arrival,ending,invalidated,true);
        }
    }
}
