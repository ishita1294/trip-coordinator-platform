package com.ishita.tripcoordinatorplatform.intelligence.commitment;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** One authorized application-owned projection; no managed entity graph or provider content. */
@Service
public class CommitmentReader {
    public record EndingEvidence(Long reservationId,Coordinates coordinates,String name,String address,String placeId,long version,boolean confirmed) {}
    public record Scheduled(Long id, String title, LocalDateTime start, String timeZone, String zoneSource,
                            Coordinates coordinates, String placeName, String address, String locality, boolean flight,
                            LocalDateTime end, String endTimeZone, Long reservationId, Integer documentedArrivalLeadMinutes, boolean hard, String arrivalRequirementIssue, ConfirmedArrivalEvidence.Source arrivalSource,EndingEvidence endingEvidence) {
        public Scheduled(Long id,String title,LocalDateTime start,String timeZone,String zoneSource,Coordinates coordinates,String placeName,String address,String locality,boolean flight,LocalDateTime end,String endTimeZone,Long reservationId,Integer lead,boolean hard,String issue,ConfirmedArrivalEvidence.Source source){this(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,end,endTimeZone,reservationId,lead,hard,issue,source,null);}
        public Scheduled(Long id,String title,LocalDateTime start,String timeZone,String zoneSource,Coordinates coordinates,String placeName,String address,String locality,boolean flight,LocalDateTime end,String endTimeZone,Long reservationId,Integer lead,boolean hard,String issue){this(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,end,endTimeZone,reservationId,lead,hard,issue,null);}
        public Scheduled withArrivalZone(String zone){if(arrivalSource==null)return this;var evidence=arrivalSource.resolve(start,zone);return new Scheduled(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,end,endTimeZone,reservationId,evidence.leadMinutes(),hard,evidence.issue(),arrivalSource,endingEvidence);}
        public Scheduled(Long id,String title,LocalDateTime start,String timeZone,String zoneSource,Coordinates coordinates,String placeName,String address,String locality,boolean flight,LocalDateTime end,String endTimeZone,Long reservationId,Integer lead,boolean hard){this(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,end,endTimeZone,reservationId,lead,hard,null);}
        public Scheduled(Long id,String title,LocalDateTime start,String timeZone,String zoneSource,Coordinates coordinates,String placeName,String address,String locality,boolean flight,LocalDateTime end,String endTimeZone,Long reservationId,Integer documentedArrivalLeadMinutes) {
            this(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,end,endTimeZone,reservationId,documentedArrivalLeadMinutes,true);
        }
        public Scheduled(Long id, String title, LocalDateTime start, String timeZone, String zoneSource, Coordinates coordinates, String placeName, String address, String locality, boolean flight, LocalDateTime end, String endTimeZone, Long reservationId) {
            this(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,end,endTimeZone,reservationId,null);
        }
        public Scheduled(Long id, String title, LocalDateTime start, String timeZone, String zoneSource,
                         Coordinates coordinates, String placeName, String address, String locality, boolean flight) {
            this(id,title,start,timeZone,zoneSource,coordinates,placeName,address,locality,flight,null,timeZone,null);
        }
    }
    public record Unassigned(Long id, LocalDateTime start, LocalDateTime end) {}
    public record SourceSnapshot(LocalDate tripStart, LocalDate tripEnd, List<Scheduled> assigned, List<Unassigned> unassigned, String explorationHours, long hoursVersion) {
        public SourceSnapshot(LocalDate start,LocalDate end,List<Scheduled> assigned,List<Unassigned> unassigned){this(start,end,assigned,unassigned,null,0);}
    }
    private final JdbcTemplate jdbc;
    public CommitmentReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    // LATERAL selects the same first flight segment as the old ordered repository lookup.
    // EXISTS avoids duplicating items with multiple participants; authorization is in the root join.
    static final String SQL="""
        SELECT t.start_date,t.end_date,h.preferences::text exploration_hours,coalesce(h.version,0) hours_version,i.id,i.title,i.start_date_time,i.end_date_time,i.flexibility,
               coalesce(r.ending_location_name,u.reported_name) ending_location_name,coalesce(r.ending_location_address,u.reported_address) ending_location_address,r.ending_location_latitude,r.ending_location_longitude,r.ending_place_id,r.ending_location_source,r.ending_location_version,r.ending_location_reviewed_at,
               r.required_arrival_at,r.id reservation_id,r.type reservation_type,r.status reservation_status,r.name reservation_name,
               e.extracted_data confirmed_booking_data,r.confirmed_booking_index,r.location,r.schedule_time_zone,r.location_name,r.location_address,r.location_latitude,r.location_longitude,
               a.name activity_name,d.name destination_name,d.country,d.timezone,
               f.departure_time_zone,f.departure_airport_code,
               (EXISTS (SELECT 1 FROM itinerary_item_participants p WHERE p.itinerary_item_id=i.id AND p.trip_member_id=viewer.id)
                 OR (t.trip_type='SOLO' AND NOT EXISTS (SELECT 1 FROM itinerary_item_participants p WHERE p.itinerary_item_id=i.id))) assigned,
               EXISTS (SELECT 1 FROM itinerary_item_participants p WHERE p.itinerary_item_id=i.id) assigned_any
        FROM trip t JOIN trip_members viewer ON viewer.trip_id=t.id AND viewer.user_id=?
        LEFT JOIN explore_personal_hours h ON h.trip_id=t.id AND h.user_id=viewer.user_id
        LEFT JOIN itineraries it ON it.trip_id=t.id
        LEFT JOIN itinerary_items i ON i.itinerary_id=it.id
        LEFT JOIN reservations r ON r.id=i.reservation_id
        LEFT JOIN activity_endpoint_history u ON u.id=r.endpoint_update_id
        LEFT JOIN travel_document_extractions e ON e.id=r.confirmed_extraction_id
        LEFT JOIN activities a ON a.id=i.activity_id
        LEFT JOIN destinations d ON d.id=a.destination_id
        LEFT JOIN LATERAL (SELECT departure_time_zone,departure_airport_code FROM flight_segments
                           WHERE reservation_id=r.id AND r.type='FLIGHT' ORDER BY segment_order LIMIT 1) f ON true
        WHERE t.id=? ORDER BY i.id
        """;
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<Scheduled> read(Long tripId,Long userId) { return snapshot(tripId,userId).assigned(); }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public SourceSnapshot snapshot(Long tripId,Long userId) {
        var assigned=new ArrayList<Scheduled>();var unassigned=new ArrayList<Unassigned>();
        String[] hours={null};long[] hoursVersion={0};
        var dates=jdbc.query(SQL,(row,index)-> {
            hours[0]=row.getString("exploration_hours");hoursVersion[0]=row.getLong("hours_version");
            Long id=row.getObject("id",Long.class);
            if(id!=null && !"CANCELLED".equals(row.getString("reservation_status"))) {
                var start=row.getObject("start_date_time",LocalDateTime.class);var end=row.getObject("end_date_time",LocalDateTime.class);
                boolean hard=!"FLEXIBLE".equals(row.getString("flexibility")) || "CONFIRMED".equals(row.getString("reservation_status"));
                if(row.getBoolean("assigned")) {
                    boolean flight="FLIGHT".equals(row.getString("reservation_type"));
                    Long reservation=row.getObject("reservation_id",Long.class);
                    String zone=row.getString("timezone"), source=row.getString("destination_name")==null ? null : "DESTINATION";
                    String name=row.getString("activity_name");if(name==null)name=row.getString("title");
                    String address=null;Coordinates point=null;
                    if(reservation!=null) {
                        name=row.getString("reservation_name");address=row.getString("location");
                        String locationName=row.getString("location_name"), locationAddress=row.getString("location_address");
                        if(locationName!=null && !locationName.isBlank())name=locationName;
                        if(locationAddress!=null && !locationAddress.isBlank())address=locationAddress;
                        var latitude=row.getBigDecimal("location_latitude");var longitude=row.getBigDecimal("location_longitude");
                        if(latitude!=null && longitude!=null)point=new Coordinates(latitude.doubleValue(),longitude.doubleValue());
                        if(!flight && row.getString("schedule_time_zone")!=null) { zone=row.getString("schedule_time_zone");source="RESERVATION"; }
                        if(flight && row.getString("departure_airport_code")!=null) { zone=row.getString("departure_time_zone");source="FLIGHT_SEGMENT";name=row.getString("departure_airport_code")+" airport"; }
                    }
                    String locality=row.getString("destination_name")==null ? null : row.getString("destination_name")+", "+row.getString("country");
                    var reviewed=row.getObject("required_arrival_at",java.time.OffsetDateTime.class);
                    var arrivalSource="ACTIVITY".equals(row.getString("reservation_type"))?new ConfirmedArrivalEvidence.Source(row.getString("confirmed_booking_data"),row.getObject("confirmed_booking_index",Integer.class),reviewed==null?null:reviewed.toInstant()):null;
                    var arrival=arrivalSource==null?new ConfirmedArrivalEvidence.Evidence(null,null):arrivalSource.resolve(start,zone);
                    var endLatitude=row.getBigDecimal("ending_location_latitude");var endLongitude=row.getBigDecimal("ending_location_longitude");
                    var ending=new EndingEvidence(reservation,endLatitude==null || endLongitude==null?null:new Coordinates(endLatitude.doubleValue(),endLongitude.doubleValue()),row.getString("ending_location_name"),row.getString("ending_location_address"),row.getString("ending_place_id"),row.getLong("ending_location_version"),row.getObject("ending_location_reviewed_at")!=null && !"UNKNOWN".equals(row.getString("ending_location_source")));
                    assigned.add(new Scheduled(id,row.getString("title"),start,zone,source,point,name,address,locality,flight,flight ? null : end,flight ? null : zone,reservation,arrival.leadMinutes(),hard,arrival.issue(),arrivalSource,ending));
                } else if(hard && !row.getBoolean("assigned_any")) unassigned.add(new Unassigned(id,start,end));
            }
            return new LocalDate[]{row.getObject("start_date",LocalDate.class),row.getObject("end_date",LocalDate.class)};
        },userId,tripId);
        if(dates.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Traveler must belong to this trip");
        return new SourceSnapshot(dates.getFirst()[0],dates.getFirst()[1],List.copyOf(assigned),List.copyOf(unassigned),hours[0],hoursVersion[0]);
    }
}
