package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts;
import com.ishita.tripcoordinatorplatform.ai.document.TourBookingFactsValidator;
import com.ishita.tripcoordinatorplatform.ai.document.TourReading;
import com.ishita.tripcoordinatorplatform.ai.document.TourReadingValidator;
import com.ishita.tripcoordinatorplatform.ai.document.SupplementalReadingFact;
import com.ishita.tripcoordinatorplatform.ai.document.BookingFact;
import com.ishita.tripcoordinatorplatform.ai.document.BookingEligibility;
import java.util.List;
import java.util.Map;

/** Original model claims and deterministic calculations are separate; neither is an accepted booking. */
public record TourReadingReview(Long documentId, Long extractionId, int schemaVersion,
        boolean confirmationSupported, String confirmationBlockCode,
        TourBookingFacts.Provenance serverProvenance, List<BookingReview> bookings,
        List<SupplementalReadingFact> supplementalFacts, String supplementalCoverageWarning) {
    public TourReadingReview { bookings = List.copyOf(bookings); supplementalFacts = List.copyOf(supplementalFacts); }
    public record BookingReview(int bookingIndex, TourBookingFacts.Booking originalFacts,
                                TourBookingFactsValidator.Timing deterministicTiming,
                                com.ishita.tripcoordinatorplatform.ai.document.BookingProposal proposal,
                                java.time.LocalDateTime calculatedArrivalLocal,
                                TourReading.Booking originalFactsV3,
                                Map<BookingFact.Field,TourReadingValidator.Verification> calculationVerifications,
                                BookingEligibility.Booking eligibility) {
        public BookingReview { calculationVerifications = Map.copyOf(calculationVerifications); }
        public BookingReview(int index,TourBookingFacts.Booking facts,TourBookingFactsValidator.Timing timing,
                com.ishita.tripcoordinatorplatform.ai.document.BookingProposal proposal,java.time.LocalDateTime arrival) {
            this(index,facts,timing,proposal,arrival,null,Map.of(),null);
        }
    }

    @com.fasterxml.jackson.annotation.JsonProperty("lifecycleClarificationSupported")
    public boolean lifecycleClarificationSupported(){
        return schemaVersion==3 && TourReading.PROMPT_VERSION.equals(serverProvenance.promptVersion())
                && !"SEMANTIC_CONFIRMATION_NOT_ENABLED".equals(confirmationBlockCode);
    }
    /** V3 source review; booking proposals require an independently enabled local workflow. */
    public static TourReadingReview fromReading(Long documentId,Long extractionId,TourReading extraction) {
        return fromReading(documentId,extractionId,extraction,false);
    }
    public static TourReadingReview fromReading(Long documentId,Long extractionId,TourReading extraction,boolean workflowEnabled) {
        var checks = new TourReadingValidator().validate(extraction,extraction.provenance().sourceSha256());
        var selection=BookingEligibility.assess(extraction);
        return new TourReadingReview(documentId,extractionId,3,workflowEnabled && selection.confirmationAllowed(),
                workflowEnabled?selection.blockCode():"SEMANTIC_CONFIRMATION_NOT_ENABLED",
                extraction.provenance(),extraction.bookings().stream().map(b -> {
                    var calculations = new java.util.EnumMap<BookingFact.Field,TourReadingValidator.Verification>(BookingFact.Field.class);
                    checks.calculations().forEach((ref,result) -> { if(ref.bookingIndex()==b.bookingIndex()) calculations.put(ref.field(),result); });
                    var proposal=workflowEnabled?com.ishita.tripcoordinatorplatform.ai.document.BookingProposal.fromReading(b,checks.calculations()):null;
                    TourBookingFactsValidator.Timing timing=null;
                    if(workflowEnabled && selection.bookings().get(b.bookingIndex()).disposition()==BookingEligibility.Disposition.ACTIVE_ELIGIBLE)try{timing=com.ishita.tripcoordinatorplatform.ai.document.BookingProposal.readingTiming(b,checks.calculations());}
                    catch(com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException unresolved){/* Retain readable local facts; confirmation validates strictly. */}
                    var arrival=timing==null || timing.requiredArrival()==null?null:java.time.LocalDateTime.ofInstant(timing.requiredArrival(),java.time.ZoneId.of(b.timezone().value()));
                    return new BookingReview(b.bookingIndex(),null,timing,proposal,arrival,b,calculations,selection.bookings().get(b.bookingIndex()));
                }).toList(),extraction.supplementalFacts(),extraction.supplementalCoverageWarning());
    }
}
