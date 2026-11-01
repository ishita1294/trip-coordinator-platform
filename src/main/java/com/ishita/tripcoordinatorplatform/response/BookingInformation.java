package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest;
import java.time.*;
import java.util.*;

/** Current saved values and private source claims are separate; neither overwrites the other. */
public record BookingInformation(Long reservationId, Presence presence, CurrentBooking current, Source source) {
    public enum Presence { SAVED, RESERVATION_DELETED }
    public enum SourceStatus { AVAILABLE, RESTRICTED, UNAVAILABLE, UNSUPPORTED_CONTRACT, INCONSISTENT }
    public record CurrentBooking(ReservationType type, String name, String provider, String reference,
            ReservationConfirmationType referenceType, ReservationStatus status,
            LocalDateTime start, LocalDateTime end, String timeZone, Instant requiredArrivalAt,
            String location, Location meetingPoint, Location endingPoint, String endingPlaceId,
            String endingLocationSource, long endingLocationVersion,
            Reservation.EndpointClarification initialEndpointClarification, Reservation.EndpointUpdate endpointAmendment) {}
    public record Source(SourceStatus status, String reason, Details details) {}
    public record Confirmation(Long receiptId, Long confirmedBy, Instant confirmedAt,
            Long historicalReservationId, Long acceptedReviewId, Long acceptedReviewVersion) {}
    public record AcceptedReview(Long reviewId, long version, Long acceptedBy, Instant acceptedAt,
            List<TourCorrectionRequest.Change> changes, List<TourSourceAssessment.Finding> assessments) {}
    public record Details(Long documentId, Long extractionId, int bookingIndex, int schemaVersion,
            String extractionSha256, SemanticTourExtraction.Provenance provenance,
            SemanticTourExtractionV3.Booking originalBooking,
            Map<SemanticFact.Field,SemanticTourExtractionV3Validator.Verification> originalCalculationVerifications,
            List<SemanticSupplementalFactV3> applicableFacts,
            List<SemanticSupplementalFactV3> documentInformation,
            List<SemanticSupplementalFactV3> unresolvedApplicability,
            String coverageWarning, List<AcceptedReview> acceptedReviews, Confirmation confirmation) {}
}
