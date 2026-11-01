package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Reservation() {
    }

    // Current authoritative timezone; source evidence below remains immutable.
    // Changes require a future controlled correction path, not request binding.
    @Column(name = "schedule_time_zone", length = 100)
    private String scheduleTimeZone;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_extraction_id", updatable = false)
    private TravelDocumentExtraction confirmedExtraction;

    @JsonIgnore
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="correction_review_id",updatable=false)
    private TourCorrectionReview correctionReview;
    public void recordCorrectionReview(TourCorrectionReview review){
        if(id!=null || correctionReview!=null || review==null || review.acceptedAt==null || confirmedExtraction==null
                || !confirmedExtraction.getId().equals(review.extractionId))throw new IllegalArgumentException("Use an accepted correction for this extraction during confirmation only.");
        correctionReview=review;
    }
    @JsonProperty(access=JsonProperty.Access.READ_ONLY)
    public Long getCorrectionReviewId(){return correctionReview==null?null:correctionReview.getId();}

    // Zero-based index in the immutable extraction's bookings array.
    @JsonIgnore
    @Column(name = "confirmed_booking_index", updatable = false)
    private Integer confirmedBookingIndex;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getScheduleTimeZone() {
        return scheduleTimeZone;
    }

    @JsonIgnore
    public TravelDocumentExtraction getConfirmedExtraction() {
        return confirmedExtraction;
    }

    @JsonIgnore
    public Integer getConfirmedBookingIndex() {
        return confirmedBookingIndex;
    }

    public record BookingSource(Long extractionId,Integer bookingIndex) {}
    @JsonProperty(access=JsonProperty.Access.READ_ONLY)
    public BookingSource getConfirmedBookingSource(){return confirmedExtraction==null?null:new BookingSource(confirmedExtraction.getId(),confirmedBookingIndex);}

    public void recordConfirmedSchedule(String timeZone, TravelDocumentExtraction extraction, int bookingIndex) {
        if (id != null || confirmedExtraction != null || extraction == null || bookingIndex < 0) {
            throw new IllegalArgumentException("Confirmed schedule provenance must be assigned once with a valid booking index");
        }
        if (timeZone != null) java.time.ZoneId.of(timeZone);
        scheduleTimeZone = timeZone;
        confirmedExtraction = extraction;
        confirmedBookingIndex = bookingIndex;
    }

    @Column(name="required_arrival_at",updatable=false)
    @JsonProperty(access=JsonProperty.Access.READ_ONLY)
    private java.time.Instant requiredArrivalAt;
    @Column(name="arrival_reviewed_at",updatable=false)
    @JsonProperty(access=JsonProperty.Access.READ_ONLY)
    private java.time.Instant arrivalReviewedAt;
    public java.time.Instant getRequiredArrivalAt(){return requiredArrivalAt;}
    public java.time.Instant getArrivalReviewedAt(){return arrivalReviewedAt;}
    public void recordReviewedArrival(java.time.Instant arrival){
        if(id!=null || confirmedExtraction==null || requiredArrivalAt!=null || arrival==null) throw new IllegalArgumentException("Reviewed arrival must be recorded once during document confirmation.");
        requiredArrivalAt=arrival;arrivalReviewedAt=java.time.Instant.now();
    }

    public Long getId() {
        return id;
    }

    @ManyToOne
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationType type;

    public ReservationType getType() {
        return type;
    }
    public void setType(ReservationType type) {
        this.type = type;
    }

    @NotBlank(message = "Reservation name is required")
    @Column(nullable = false)
    private String name;

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    @NotNull(message = "Reservation start time is required")
    @Column(nullable = false)
    private LocalDateTime startDateTime;

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }
    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
    }

    private LocalDateTime endDateTime;

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }
    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;
    }

    private String provider;

    public String getProvider() {
        return provider;
    }
    public void setProvider(String provider) {
        this.provider = provider;
    }

    private String confirmationNumber;

    public String getConfirmationNumber() {
        return confirmationNumber;
    }
    public void setConfirmationNumber(String confirmationNumber) {
        this.confirmationNumber = confirmationNumber;

    }

    @Enumerated(EnumType.STRING)
    private ReservationConfirmationType confirmationNumberType;

    public ReservationConfirmationType getConfirmationNumberType() {
        return confirmationNumberType;
    }

    public void setConfirmationNumberType(ReservationConfirmationType confirmationNumberType) {
        this.confirmationNumberType = confirmationNumberType;
    }

    @Embedded
    @jakarta.persistence.AttributeOverrides({
        @jakarta.persistence.AttributeOverride(name="name",column=@Column(name="ending_location_name")),
        @jakarta.persistence.AttributeOverride(name="address",column=@Column(name="ending_location_address",length=1000)),
        @jakarta.persistence.AttributeOverride(name="latitude",column=@Column(name="ending_location_latitude",precision=10,scale=7)),
        @jakarta.persistence.AttributeOverride(name="longitude",column=@Column(name="ending_location_longitude",precision=10,scale=7))})
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private Location endingLocation;
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name="ending_place_id") private String endingPlaceId;
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name="ending_location_source") private String endingLocationSource;
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name="ending_location_reviewed_at") private java.time.Instant endingLocationReviewedAt;
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name="ending_location_version",nullable=false) private long endingLocationVersion;
    @Column(name="endpoint_clarification_actor_id",updatable=false) private Long endpointClarificationActorId;
    @Column(name="endpoint_clarification_at",updatable=false) private java.time.Instant endpointClarificationAt;
    @Column(name="endpoint_clarification_choice",updatable=false) private String endpointClarificationChoice;
    @Column(name="endpoint_clarification_name",updatable=false) private String endpointClarificationName;
    @Column(name="endpoint_clarification_address",length=1000,updatable=false) private String endpointClarificationAddress;
    public record EndpointClarification(String provenance,String choice,Long actorId,java.time.Instant reportedAt,String reportedName,String reportedAddress) {}
    @JsonProperty(access=JsonProperty.Access.READ_ONLY)
    public EndpointClarification getEndpointClarification(){return endpointClarificationAt==null?null:new EndpointClarification("USER_REPORTED_NOT_SOURCE_VERIFIED",endpointClarificationChoice,endpointClarificationActorId,endpointClarificationAt,endpointClarificationName,endpointClarificationAddress);}
    public void recordEndpointClarification(String choice,Long actor){
        if(id!=null || confirmedExtraction==null || endpointClarificationAt!=null || actor==null || !java.util.Set.of("UNKNOWN","SAME_AS_MEETING_POINT","LOCATION").contains(choice))throw new IllegalArgumentException("Record initial endpoint clarification once with confirmed source and actor.");
        endpointClarificationChoice=choice;endpointClarificationActorId=actor;endpointClarificationAt=java.time.Instant.now();
        endpointClarificationName=endingLocation==null?null:endingLocation.getName();endpointClarificationAddress=endingLocation==null?null:endingLocation.getAddress();
    }
    @ManyToOne(fetch=FetchType.EAGER)
    @JoinColumn(name="endpoint_update_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private ActivityEndpointUpdate endpointUpdate;
    public record EndpointUpdate(Long id,String provenance,String choice,Long actorId,java.time.Instant reportedAt,String reportedName,String reportedAddress,long endpointVersion) {}
    @JsonProperty(access=JsonProperty.Access.READ_ONLY)
    public EndpointUpdate getEndpointUpdate(){return endpointUpdate==null?null:new EndpointUpdate(endpointUpdate.id,endpointUpdate.kind,endpointUpdate.choice,endpointUpdate.actorId,endpointUpdate.reportedAt,endpointUpdate.reportedName,endpointUpdate.reportedAddress,endpointUpdate.endpointVersion);}
    public void linkEndpointUpdate(ActivityEndpointUpdate amendment){
        if(amendment==null || !java.util.Objects.equals(id,amendment.reservationId) || !java.util.Objects.equals(trip.getId(),amendment.tripId) || amendment.endpointVersion!=endingLocationVersion)throw new IllegalArgumentException("Endpoint amendment does not match this booking version.");
        endpointUpdate=amendment;
    }
    public Location getEndingLocation(){
        if(endingLocation!=null)return endingLocation;
        if(endpointUpdate!=null && "LOCATION".equals(endpointUpdate.choice) && "USER_CONFIRMED_PLACE_ID".equals(endingLocationSource)){
            var reported=new Location();reported.setName(endpointUpdate.reportedName);reported.setAddress(endpointUpdate.reportedAddress);return reported;
        }
        return null;
    }
    public String getEndingPlaceId(){return endingPlaceId;}
    public String getEndingLocationSource(){return endingLocationSource;}
    public java.time.Instant getEndingLocationReviewedAt(){return endingLocationReviewedAt;}
    public long getEndingLocationVersion(){return endingLocationVersion;}
    public void reviewEndingLocation(com.ishita.tripcoordinatorplatform.request.ActivityEndingLocationRequest review){
        review.validate();
        if(type!=ReservationType.ACTIVITY)throw new IllegalArgumentException("Only activity bookings have a reviewed activity endpoint.");
        if(review.version()!=null && review.version()!=endingLocationVersion)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"The activity endpoint changed. Refresh before saving.");
        Location selected="SAME_AS_MEETING_POINT".equals(review.choice())?structuredLocation:"LOCATION".equals(review.choice())?review.location():null;
        if("SAME_AS_MEETING_POINT".equals(review.choice()) && (selected==null || (selected.getName()==null || selected.getName().isBlank()) && (selected.getAddress()==null || selected.getAddress().isBlank())))throw new IllegalArgumentException("The booking has no meeting point to confirm as its endpoint.");
        boolean same="SAME_AS_MEETING_POINT".equals(review.choice());
        boolean enrichSame=same && review.placeId()!=null && "USER_CONFIRMED_SAME_AS_MEETING".equals(endingLocationSource);
        if(review.placeId()!=null && !same)selected=null; // Persist Google identity only; never cache its content in the booking.
        if(selected!=null){var copy=new Location();copy.setName(selected.getName());copy.setAddress(selected.getAddress());copy.setLatitude(selected.getLatitude());copy.setLongitude(selected.getLongitude());endingLocation=copy;}else endingLocation=null;
        endingPlaceId=review.placeId();endingLocationSource=same?"USER_CONFIRMED_SAME_AS_MEETING":review.placeId()!=null?"USER_CONFIRMED_PLACE_ID":"UNKNOWN".equals(review.choice())?"UNKNOWN":"USER_CONFIRMED";
        if(!enrichSame)endingLocationReviewedAt=java.time.Instant.now();endingLocationVersion++;
    }

    @Valid
    @Embedded
    private Location structuredLocation;

    public Location getStructuredLocation() {
        return structuredLocation;
    }

    public void setStructuredLocation(Location structuredLocation) {
        if (!sameLocation(this.structuredLocation,structuredLocation) && "USER_CONFIRMED_SAME_AS_MEETING".equals(endingLocationSource)) {
            // Same is a relationship to the authoritative meeting assertion, not a provider snapshot.
            endingPlaceId=null;
            endingLocation=copyLocation(structuredLocation);
            if(structuredLocation==null || (structuredLocation.getName()==null || structuredLocation.getName().isBlank()) && (structuredLocation.getAddress()==null || structuredLocation.getAddress().isBlank())) {
                endingLocation=null;endingLocationSource="UNKNOWN";endingLocationReviewedAt=java.time.Instant.now();
            }
            endingLocationVersion++;
        }
        this.structuredLocation = copyLocation(structuredLocation);
    }

    public static boolean sameLocation(Location a,Location b) {
        if(a==null || b==null)return a==b;
        return java.util.Objects.equals(a.getName(),b.getName()) && java.util.Objects.equals(a.getAddress(),b.getAddress())
            && sameNumber(a.getLatitude(),b.getLatitude()) && sameNumber(a.getLongitude(),b.getLongitude());
    }
    private static boolean sameNumber(java.math.BigDecimal a,java.math.BigDecimal b){return a==null || b==null?a==b:a.compareTo(b)==0;}
    private static Location copyLocation(Location original){
        if(original==null)return null;var copy=new Location();copy.setName(original.getName());copy.setAddress(original.getAddress());copy.setLatitude(original.getLatitude());copy.setLongitude(original.getLongitude());return copy;
    }

    private String location;

    public String getLocation() {
        return location;
    }
    public void setLocation(String location) {
        this.location = location;
    }

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;
    public ReservationStatus getStatus() {
        return status;
    }
    public void setStatus(ReservationStatus status) {
        this.status = status;
    }
}
