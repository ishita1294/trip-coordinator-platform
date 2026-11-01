package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Reservation> {
    java.util.Optional<Reservation> findByIdAndTrip_Id(Long id, Long tripId);

    interface EndpointMetadata { com.ishita.tripcoordinatorplatform.model.ReservationType getType();long getVersion();String getMeetingName();String getMeetingAddress();java.math.BigDecimal getMeetingLatitude();java.math.BigDecimal getMeetingLongitude();
        default com.ishita.tripcoordinatorplatform.model.Location meeting(){var l=new com.ishita.tripcoordinatorplatform.model.Location();l.setName(getMeetingName());l.setAddress(getMeetingAddress());l.setLatitude(getMeetingLatitude());l.setLongitude(getMeetingLongitude());return l;} }
    @org.springframework.data.jpa.repository.Query("select r.type as type,r.endingLocationVersion as version,r.structuredLocation.name as meetingName,r.structuredLocation.address as meetingAddress,r.structuredLocation.latitude as meetingLatitude,r.structuredLocation.longitude as meetingLongitude from Reservation r where r.id=:id and r.trip.id=:trip")
    java.util.Optional<EndpointMetadata> endingMetadata(@org.springframework.data.repository.query.Param("trip") Long trip,@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Reservation r where r.id=:id and r.trip.id=:trip")
    java.util.Optional<Reservation> lockEndingLocation(@org.springframework.data.repository.query.Param("trip") Long trip,@org.springframework.data.repository.query.Param("id") Long id);
    List<Reservation> findByTrip_Id(Long tripId);

    boolean existsByTrip_IdAndConfirmationNumber(
            Long tripId,
            String confirmationNumber
    );
}
