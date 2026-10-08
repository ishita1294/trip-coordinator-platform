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
        if (result == null || result.bookings() == null || result.bookings().isEmpty()) {
            throw new IllegalArgumentException("Attraction/tour extraction must contain at least one booking");
        }
        for (var booking : result.bookings()) {
            if (booking == null) {
                throw new IllegalArgumentException("Attraction/tour booking must not be null");
            }
            if (booking.durationMinutes() != null && booking.durationMinutes() <= 0) {
                throw new IllegalArgumentException("Extracted duration must be greater than zero");
            }
            if (booking.meetingPoint() != null && !validator.validate(booking.meetingPoint()).isEmpty()) {
                throw new IllegalArgumentException("Meeting point coordinates must be a valid latitude/longitude pair");
            }
            if (booking.timezone() != null) {
                try {
                    ZoneId.of(booking.timezone());
                } catch (DateTimeException exception) {
                    throw new IllegalArgumentException("Extracted timezone is invalid", exception);
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
