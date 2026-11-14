package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.*;
import java.time.LocalDateTime;
import java.util.List;

/** Identification details only. A result does not grant access to the booking's private source document. */
public record BookingDiscoveryResult(Status status, Long resolvedReservationId, List<Candidate> candidates,
        boolean hasMoreCandidates, String explanation, int page, int pageSize, long totalMatches, long totalPages,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DayCoverage daySearch,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) NameMatching nameMatching) {
    public enum NameMatching { WHOLE_TOKEN_FALLBACK }
    public BookingDiscoveryResult(Status status,Long resolvedReservationId,List<Candidate> candidates,boolean hasMoreCandidates,
            String explanation,int page,int pageSize,long totalMatches,long totalPages,DayCoverage daySearch){
        this(status,resolvedReservationId,candidates,hasMoreCandidates,explanation,page,pageSize,totalMatches,totalPages,daySearch,null);
    }
    public BookingDiscoveryResult(Status status,Long resolvedReservationId,List<Candidate> candidates,boolean hasMoreCandidates,
            String explanation,int page,int pageSize,long totalMatches,long totalPages){
        this(status,resolvedReservationId,candidates,hasMoreCandidates,explanation,page,pageSize,totalMatches,totalPages,null);
    }
    /** totalMatches counts definite matches; pages include definite and uncertain candidates. */
    public record DayCoverage(java.time.LocalDate date,String timeZone,java.time.Instant startInclusive,
            java.time.Instant endExclusive,boolean coverageComplete,long uncertainCount,long totalCandidates) {}
    public enum DayMatch { MATCH, UNDETERMINED }
    public enum Status { UNIQUE, AMBIGUOUS, NO_MATCH, INVALID_CRITERIA, INSUFFICIENT_CRITERIA }
    public record Candidate(Long reservationId, ReservationType type, String name, String provider,
            String bookingReference, ReservationStatus status, LocalDateTime start, LocalDateTime end, String timeZone,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long tripId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String tripName,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DayMatch dayMatch,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String daySearchUncertainty) {
        public Candidate(Long reservationId,ReservationType type,String name,String provider,String bookingReference,
                ReservationStatus status,LocalDateTime start,LocalDateTime end,String timeZone,Long tripId,String tripName){
            this(reservationId,type,name,provider,bookingReference,status,start,end,timeZone,tripId,tripName,null,null);
        }
        public Candidate(Long reservationId,ReservationType type,String name,String provider,String bookingReference,
                ReservationStatus status,LocalDateTime start,LocalDateTime end,String timeZone){
            this(reservationId,type,name,provider,bookingReference,status,start,end,timeZone,null,null);
        }
    }
    public BookingDiscoveryResult { candidates=List.copyOf(candidates); }
}
