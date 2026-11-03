package com.ishita.tripcoordinatorplatform.intelligence.planning;

import java.time.*;
import java.util.*;

/** Personal availability preferences; never booking edits or evidence of completion. */
public record ExplorationHours(LocalTime dayStart, LocalTime dayEnd, int endDayOffset, String timeZone,
                               int minimumRestMinutes, Map<LocalDate, Day> overrides) {
    public record Day(LocalTime dayStart, LocalTime dayEnd, int endDayOffset, String timeZone) {}
    public ExplorationHours {
        overrides = overrides == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(overrides));
        validate(dayStart,dayEnd,endDayOffset,timeZone);
        if(minimumRestMinutes<0 || minimumRestMinutes>1440 || overrides.size()>730) throw new IllegalArgumentException("Use a rest warning threshold of 0–1440 minutes and at most 730 day overrides.");
        overrides.forEach((date,day)->{if(date==null || day==null)throw new IllegalArgumentException("Invalid day override.");validate(day.dayStart(),day.dayEnd(),day.endDayOffset(),day.timeZone());});
    }
    public static ExplorationHours defaults() {return new ExplorationHours(LocalTime.of(9,0),LocalTime.of(22,0),0,null,360,Map.of());}
    public Day day(LocalDate date) {return overrides.getOrDefault(date,new Day(dayStart,dayEnd,endDayOffset,timeZone));}
    private static void validate(LocalTime start,LocalTime end,int offset,String zone) {
        if(start==null || end==null || offset<0 || offset>1 || offset==0 && !end.isAfter(start)) throw new IllegalArgumentException("Day end must follow day start; use next day for an overnight end.");
        if(start.getSecond()!=0 || start.getNano()!=0 || end.getSecond()!=0 || end.getNano()!=0) throw new IllegalArgumentException("Use whole minutes for exploration hours.");
        if(zone!=null && !ZoneId.getAvailableZoneIds().contains(zone)) throw new IllegalArgumentException("Use an IANA timezone, such as Asia/Kolkata.");
    }
}
