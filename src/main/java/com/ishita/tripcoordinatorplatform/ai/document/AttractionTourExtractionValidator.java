package com.ishita.tripcoordinatorplatform.ai.document;

import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

@Component
public class AttractionTourExtractionValidator {
    private final Validator validator;

    public AttractionTourExtractionValidator(Validator validator) {
        this.validator = validator;
    }

    // Missing facts remain reviewable; invalid supplied values are malformed AI output.
    public void validate(AttractionTourExtractionResult result) {
        if (result == null || result.bookings() == null) {
            throw new InvalidDocumentExtractionException(InvalidDocumentExtractionException.Code.MISSING_BOOKINGS,
                    "Attraction/tour extraction must contain at least one booking");
        }
        if (result.bookings().isEmpty()) {
            throw new InvalidDocumentExtractionException(InvalidDocumentExtractionException.Code.EMPTY_BOOKINGS,
                    "Attraction/tour extraction must contain at least one booking");
        }
        for (var booking : result.bookings()) {
            if (booking == null) {
                throw new InvalidDocumentExtractionException("Attraction/tour booking must not be null");
            }
            if (booking.arrivalLeadMinutes() != null && booking.arrivalLeadMinutes() < 0) throw new InvalidDocumentExtractionException("Invalid documented arrival margin");
            if (booking.durationMinutes() != null && booking.durationMinutes() <= 0) {
                throw new InvalidDocumentExtractionException("Extracted duration must be greater than zero");
            }
            if (booking.meetingPoint() != null && !validator.validate(booking.meetingPoint()).isEmpty()) {
                throw new InvalidDocumentExtractionException("Meeting point coordinates must be a valid latitude/longitude pair");
            }
            if(booking.endingPoint()!=null && !validator.validate(booking.endingPoint()).isEmpty()) throw new InvalidDocumentExtractionException("Ending point coordinates must be a valid latitude/longitude pair");
            if (booking.timezone() != null) {
                try {
                    ZoneId.of(booking.timezone());
                } catch (DateTimeException exception) {
                    throw new InvalidDocumentExtractionException("Extracted timezone is invalid", exception);
                }
            }
        }
    }

    public void validateForConfirmation(AttractionTourExtractionResult result) {
        validate(result);
        for (var booking : result.bookings()) {
            if (booking.name() == null || booking.name().isBlank()
                    || booking.startDate() == null || booking.startTime() == null) {
                throw new IllegalArgumentException(
                        "Attraction/tour extraction is incomplete. Reprocess the document to extract a name and start date/time.");
            }
        }
    }
}
