package com.ishita.tripcoordinatorplatform.ai.document;

import com.ishita.tripcoordinatorplatform.model.Location;

import java.time.LocalDate;
import java.time.LocalTime;

public record AttractionTourBookingExtraction(
        String name,
        String bookingReference,
        String provider,
        LocalDate startDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        Integer durationMinutes,
        Location meetingPoint,
        String timezone,
        String instructions
) {
}
