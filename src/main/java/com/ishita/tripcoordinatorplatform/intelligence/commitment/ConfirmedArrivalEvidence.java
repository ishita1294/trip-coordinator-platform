package com.ishita.tripcoordinatorplatform.intelligence.commitment;

import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Exact confirmed booking evidence; clock times never imply a guessed date or timezone. */
public final class ConfirmedArrivalEvidence {
    private ConfirmedArrivalEvidence() {}
    public record Evidence(Integer leadMinutes, String issue) {}
    public record Source(String json,Integer bookingIndex,Instant reviewedArrivalAt) {
        public Evidence resolve(LocalDateTime start,String zone){return reviewedArrivalAt!=null?reviewed(reviewedArrivalAt,start,zone):inspect(json,bookingIndex,start,zone);}
    }
    private static final Pattern EARLY = Pattern.compile("(?i)\\barriv(?:e|al)\\s+(?:at least\\s+)?(\\d{1,4})[ -]+minutes?\\s+(?:early|before)\\b|\\b(\\d{1,4})[ -]+minutes?\\s+arrival buffer\\s+before\\b|\\b(\\d{1,4})[ -]+minutes?\\s+before (?:the )?(?:tour|activity) starts?\\b");
    private static final Pattern CLOCK = Pattern.compile("(?i)\\b(?:arrive\\s+(?:at\\s+the\\s+meeting\\s+point\\s+)?by|required\\s+arrival(?:\\s+time)?\\s*[:=]?)\\s*(?:(\\d{4}-\\d{2}-\\d{2})[ T,]+)?(\\d{1,2})(?::(\\d{2}))?(?![ -]+minutes?\\b)\\s*([ap]\\.?m\\.?)?(?:\\s+(IST|UTC|GMT|[A-Za-z_]+/[A-Za-z_/]+|(?-i:[A-Z]{2,5})))?\\b(?![ -]+minutes?\\b)");
    private static final Pattern ARRIVAL_TEXT = Pattern.compile("(?i)\\b(?:arrive\\s+(?:at\\s+the\\s+meeting\\s+point\\s+)?by|required\\s+arrival)\\b");
    static Integer read(String json,Integer index) {return inspect(json,index,null,null).leadMinutes();}
    public static Evidence inspect(String json,Integer index,LocalDateTime start,String zone) {
        if(json==null || index==null || index<0) return new Evidence(null,null);
        try {
            var bookings=JsonMapper.builder().build().readTree(json).path("bookings");
            if(!bookings.isArray() || index>=bookings.size()) return issue("The linked extraction booking cannot be verified.");
            var booking=bookings.path(index);var values=new HashSet<Integer>();
            var explicit=booking.path("arrivalLeadMinutes");
            if(!explicit.isMissingNode() && !explicit.isNull()) {
                if(!explicit.isIntegralNumber() || !explicit.canConvertToInt() || !valid(explicit.intValue())) return issue("Invalid structured arrival requirement.");
                values.add(explicit.intValue());
            }
            String instructions=booking.path("instructions").asText("");
            var relative=EARLY.matcher(instructions);boolean relativeFound=false;
            while(relative.find()) {
                relativeFound=true;
                int lead=Integer.parseInt(relative.group(1)!=null?relative.group(1):relative.group(2)!=null?relative.group(2):relative.group(3));
                if(!valid(lead)) return issue("Invalid documented arrival margin.");values.add(lead);
            }
            var clocks=CLOCK.matcher(instructions);boolean clockFound=false;
            while(clocks.find()) {
                clockFound=true;
                // Compatibility helper has no schedule context. Production always supplies it.
                if(start==null) {if(values.isEmpty())return issue("Arrival clock needs the authoritative activity date and time.");continue;}
                int hour=Integer.parseInt(clocks.group(2)),minute=clocks.group(3)==null?0:Integer.parseInt(clocks.group(3));
                String meridiem=clocks.group(4);
                if(minute>59 || meridiem!=null && (hour<1 || hour>12) || meridiem==null && (hour>23 || clocks.group(3)==null || hour>0 && hour<=12 && !clocks.group(2).startsWith("0"))) return issue("Arrival clock is ambiguous or invalid; confirm its full date and time.");
                if(meridiem!=null) hour=hour%12+(meridiem.toLowerCase(Locale.ROOT).startsWith("p")?12:0);
                var date=clocks.group(1)==null?start.toLocalDate():LocalDate.parse(clocks.group(1));
                var arrival=date.atTime(hour,minute);
                if(clocks.group(1)==null && arrival.isAfter(start)) return issue("Arrival clock may refer to the previous day; confirm the arrival date.");
                if(zone==null || zone.isBlank()) return issue("Confirm the activity timezone before interpreting its arrival clock.");
                var startAt=strict(start,zone);var arrivalAt=strict(arrival,zone);
                String label=clocks.group(5);
                if(label!=null && !matchesZone(label,zone,arrivalAt)) return issue("Arrival timezone contradicts or cannot be verified against the activity timezone.");
                long seconds=Duration.between(arrivalAt,startAt).getSeconds();
                if(seconds<0 || seconds/60>Integer.MAX_VALUE || seconds%60!=0) return issue("Arrival must be at or before the start, in whole minutes.");
                values.add((int)(seconds/60));
            }
            if(!clockFound && !relativeFound && ARRIVAL_TEXT.matcher(instructions).find()) return issue("The documented arrival requirement needs review.");
            if(values.size()>1) return issue("Structured and documented arrival requirements conflict; review the required arrival.");
            return new Evidence(values.isEmpty()?null:values.iterator().next(),null);
        } catch(Exception invalid) {return issue("Arrival evidence is invalid or its local time is ambiguous; review the required arrival.");}
    }
    public static Evidence reviewed(Instant arrival,LocalDateTime start,String zone) {
        try {
            long seconds=Duration.between(arrival,strict(start,zone)).getSeconds();
            if(seconds<0 || seconds/60>Integer.MAX_VALUE || seconds%60!=0) return issue("Reviewed arrival is inconsistent with the current activity schedule.");
            return new Evidence((int)(seconds/60),null);
        } catch(Exception invalid) {return issue("The reviewed arrival needs the resolved activity timezone and start.");}
    }
    public static Instant strict(LocalDateTime local,String zone) {
        var offsets=ZoneId.of(zone).getRules().getValidOffsets(local);
        if(offsets.size()!=1) throw new IllegalArgumentException("Local time is ambiguous or nonexistent.");
        return local.toInstant(offsets.getFirst());
    }
    private static boolean matchesZone(String label,String zone,Instant at) {
        if(label.equalsIgnoreCase("IST")) return zone.equals("Asia/Kolkata");
        if(label.equalsIgnoreCase("UTC") || label.equalsIgnoreCase("GMT")) return ZoneId.of(zone).getRules().getOffset(at).equals(ZoneOffset.UTC);
        if(label.contains("/")) return label.equals(zone);
        return label.equalsIgnoreCase(DateTimeFormatter.ofPattern("z",Locale.ENGLISH).format(at.atZone(ZoneId.of(zone))));
    }
    public record Review(int bookingIndex,LocalDateTime requiredArrivalLocal,String timeZone,String issue) {}
    public static List<Review> review(String json) {
        var bookings=JsonMapper.builder().build().readTree(json).path("bookings");
        if(!bookings.isArray())return List.of();
        var result=new ArrayList<Review>();
        for(int i=0;i<bookings.size();i++) {
            var booking=bookings.path(i);String zone=booking.path("timezone").asText(null);
            try {
                var start=LocalDate.parse(booking.path("startDate").asText()).atTime(LocalTime.parse(booking.path("startTime").asText()));
                var evidence=inspect(json,i,start,zone);
                LocalDateTime arrival=evidence.leadMinutes()==null?null:zone==null?start.minusMinutes(evidence.leadMinutes()):LocalDateTime.ofInstant(strict(start,zone).minusSeconds(evidence.leadMinutes()*60L),ZoneId.of(zone));
                result.add(new Review(i,arrival,zone,evidence.issue()));
            } catch(Exception invalid) {result.add(new Review(i,null,zone,"Resolve the activity date/time and timezone before reviewing arrival."));}
        }
        return List.copyOf(result);
    }
    private static boolean valid(int value) {return value>=0;}
    private static Evidence issue(String text) {return new Evidence(null,text);}
}
