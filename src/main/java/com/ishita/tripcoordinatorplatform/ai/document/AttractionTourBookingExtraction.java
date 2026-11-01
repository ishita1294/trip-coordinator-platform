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
        String instructions,
        Integer arrivalLeadMinutes, Location endingPoint
) {
    public AttractionTourBookingExtraction(String name,String bookingReference,String provider,LocalDate startDate,LocalTime startTime,LocalDate endDate,LocalTime endTime,Integer durationMinutes,Location meetingPoint,String timezone,String instructions,Integer arrivalLeadMinutes) {
        this(name,bookingReference,provider,startDate,startTime,endDate,endTime,durationMinutes,meetingPoint,timezone,instructions,arrivalLeadMinutes,null);
    }
    public AttractionTourBookingExtraction(String name, String bookingReference, String provider,
            LocalDate startDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
            Integer durationMinutes, Location meetingPoint, String timezone, String instructions) {
        this(name,bookingReference,provider,startDate,startTime,endDate,endTime,durationMinutes,meetingPoint,timezone,instructions,null);
    }
}
