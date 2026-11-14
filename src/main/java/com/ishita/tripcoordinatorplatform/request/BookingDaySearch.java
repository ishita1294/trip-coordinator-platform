package com.ishita.tripcoordinatorplatform.request;

import java.time.*;

/** A local day in a stated timezone. A null date means today only until resolved for a turn. */
public record BookingDaySearch(LocalDate date,String timeZone) {
    private static final java.util.Set<String> TIME_ZONES=java.util.Set.copyOf(ZoneId.getAvailableZoneIds());
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknownField(String name,Object value){throw new IllegalArgumentException("Unsupported day search field.");}
    public static String validTimeZone(String value){
        if(value==null || value.length()>100)return null;
        String zone=value.strip();
        return TIME_ZONES.contains(zone)?zone:null;
    }
    public BookingDaySearch resolve(Instant asOf,String deviceTimeZone){
        String zone=validTimeZone(timeZone==null?deviceTimeZone:timeZone);
        if(zone==null)throw new TimeZoneRequired();
        var resolved=new BookingDaySearch(date==null?asOf.atZone(ZoneId.of(zone)).toLocalDate():date,zone);
        resolved.requireResolved();return resolved;
    }
    public void requireResolved(){
        if(date==null || validTimeZone(timeZone)==null)throw new IllegalArgumentException("A resolved local date and IANA timezone are required.");
        if(!start().isBefore(end()))throw new IllegalArgumentException("The local day does not exist in this timezone.");
    }
    public Instant start(){return date.atStartOfDay(ZoneId.of(timeZone)).toInstant();}
    public Instant end(){return date.plusDays(1).atStartOfDay(ZoneId.of(timeZone)).toInstant();}
    /** Allows the model to request clarification without executing a search in a guessed timezone. */
    public static final class TimeZoneRequired extends IllegalArgumentException {}
}
