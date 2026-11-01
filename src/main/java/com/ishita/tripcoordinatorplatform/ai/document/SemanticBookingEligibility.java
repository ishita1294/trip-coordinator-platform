package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticFact.Status.CLEAR;

/** Deterministic classification of already validated documentary claims. No reservation mutations or wording interpretation. */
public final class SemanticBookingEligibility {
    private SemanticBookingEligibility() {}
    public enum Disposition { ACTIVE_ELIGIBLE, HISTORICAL, REQUIRES_REVIEW }
    public record Booking(int bookingIndex,Disposition disposition,SemanticBookingLifecycleV3.State lifecycleState,String blockCode) {}
    public record Selection(List<Booking> bookings,List<Integer> activeIndexes,String blockCode) {
        public Selection {bookings=List.copyOf(bookings);activeIndexes=List.copyOf(activeIndexes);}
        public boolean confirmationAllowed(){return blockCode==null;}
    }
    public static Selection assess(SemanticTourExtractionV3 extraction) {return assess(extraction,Map.of());}
    public static Selection assess(SemanticTourExtractionV3 extraction,Map<Integer,SemanticBookingLifecycleV3.State> userStates) {
        SemanticBookingLifecycleV3.validateEffectiveStates(extraction,userStates);
        var rows=new ArrayList<Booking>();var active=new ArrayList<Integer>();boolean pending=false;
        for(var b:extraction.bookings()) {
            var f=b.lifecycle();var state=userStates.getOrDefault(b.bookingIndex(),f.status()==CLEAR?f.value():SemanticBookingLifecycleV3.State.UNKNOWN);
            Disposition disposition;String code=null;
            switch(state) {
                case CANCELLED,SUPERSEDED -> disposition=Disposition.HISTORICAL;
                case ACTIVE -> {
                    if(b.replaces().stream().anyMatch(r->r.status()!=CLEAR)){
                        disposition=Disposition.REQUIRES_REVIEW;code="BOOKING_REPLACEMENT_UNRESOLVED";
                    }else {disposition=Disposition.ACTIVE_ELIGIBLE;active.add(b.bookingIndex());}
                }
                default -> {disposition=Disposition.REQUIRES_REVIEW;code="BOOKING_LIFECYCLE_UNESTABLISHED";}
            }
            pending|=disposition==Disposition.REQUIRES_REVIEW;
            rows.add(new Booking(b.bookingIndex(),disposition,state,code));
        }
        // The existing document lifecycle has no partial-confirmation state. Do not consume pending entries.
        return new Selection(rows,active,pending?"BOOKING_LIFECYCLE_REVIEW_REQUIRED":active.isEmpty()?"NO_ACTIVE_BOOKINGS":null);
    }
}
