package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts;
import com.ishita.tripcoordinatorplatform.ai.document.TourBookingFactsValidator;
import com.ishita.tripcoordinatorplatform.ai.document.SemanticTourExtractionV3;
import com.ishita.tripcoordinatorplatform.ai.document.SemanticTourExtractionV3Validator;
import com.ishita.tripcoordinatorplatform.ai.document.SemanticSupplementalFactV3;
import com.ishita.tripcoordinatorplatform.ai.document.BookingFact;
import com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingEligibility;
import java.util.List;
import java.util.Map;

/** Original model claims and deterministic calculations are separate; neither is an accepted booking. */
public record SemanticTourReview(Long documentId, Long extractionId, int schemaVersion,
        boolean confirmationSupported, String confirmationBlockCode,
        TourBookingFacts.Provenance serverProvenance, List<BookingReview> bookings,
        List<SemanticSupplementalFactV3> supplementalFacts, String supplementalCoverageWarning) {
    public SemanticTourReview { bookings = List.copyOf(bookings); supplementalFacts = List.copyOf(supplementalFacts); }
    public record BookingReview(int bookingIndex, TourBookingFacts.Booking originalFacts,
                                TourBookingFactsValidator.Timing deterministicTiming,
                                com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingProposal proposal,
                                java.time.LocalDateTime calculatedArrivalLocal,
                                SemanticTourExtractionV3.Booking originalFactsV3,
                                Map<BookingFact.Field,SemanticTourExtractionV3Validator.Verification> calculationVerifications,
                                SemanticBookingEligibility.Booking eligibility) {
        public BookingReview { calculationVerifications = Map.copyOf(calculationVerifications); }
        public BookingReview(int index,TourBookingFacts.Booking facts,TourBookingFactsValidator.Timing timing,
                com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingProposal proposal,java.time.LocalDateTime arrival) {
            this(index,facts,timing,proposal,arrival,null,Map.of(),null);
        }
    }

    @com.fasterxml.jackson.annotation.JsonProperty("lifecycleClarificationSupported")
    public boolean lifecycleClarificationSupported(){
        return schemaVersion==3 && SemanticTourExtractionV3.PROMPT_VERSION.equals(serverProvenance.promptVersion())
                && !"SEMANTIC_CONFIRMATION_NOT_ENABLED".equals(confirmationBlockCode);
    }
    /** V3 source review; booking proposals require an independently enabled local workflow. */
    public static SemanticTourReview fromV3(Long documentId,Long extractionId,SemanticTourExtractionV3 extraction) {
        return fromV3(documentId,extractionId,extraction,false);
    }
    public static SemanticTourReview fromV3(Long documentId,Long extractionId,SemanticTourExtractionV3 extraction,boolean workflowEnabled) {
        var checks = new SemanticTourExtractionV3Validator().validate(extraction,extraction.provenance().sourceSha256());
        var selection=SemanticBookingEligibility.assess(extraction);
        return new SemanticTourReview(documentId,extractionId,3,workflowEnabled && selection.confirmationAllowed(),
                workflowEnabled?selection.blockCode():"SEMANTIC_CONFIRMATION_NOT_ENABLED",
                extraction.provenance(),extraction.bookings().stream().map(b -> {
                    var calculations = new java.util.EnumMap<BookingFact.Field,SemanticTourExtractionV3Validator.Verification>(BookingFact.Field.class);
                    checks.calculations().forEach((ref,result) -> { if(ref.bookingIndex()==b.bookingIndex()) calculations.put(ref.field(),result); });
                    var proposal=workflowEnabled?com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingProposal.fromV3(b,checks.calculations()):null;
                    TourBookingFactsValidator.Timing timing=null;
                    if(workflowEnabled && selection.bookings().get(b.bookingIndex()).disposition()==SemanticBookingEligibility.Disposition.ACTIVE_ELIGIBLE)try{timing=com.ishita.tripcoordinatorplatform.ai.document.SemanticBookingProposal.timingV3(b,checks.calculations());}
                    catch(com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException unresolved){/* Retain readable local facts; confirmation validates strictly. */}
                    var arrival=timing==null || timing.requiredArrival()==null?null:java.time.LocalDateTime.ofInstant(timing.requiredArrival(),java.time.ZoneId.of(b.timezone().value()));
                    return new BookingReview(b.bookingIndex(),null,timing,proposal,arrival,b,calculations,selection.bookings().get(b.bookingIndex()));
                }).toList(),extraction.supplementalFacts(),extraction.supplementalCoverageWarning());
    }
}
