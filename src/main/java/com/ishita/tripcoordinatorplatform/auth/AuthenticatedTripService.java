package com.ishita.tripcoordinatorplatform.auth;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;

/** Lists and manages trips for the signed-in user. Creating a trip also creates its owner membership and itinerary in one transaction. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthenticatedTripService {
    public record CreatePersonalTrip(@NotBlank @Size(max = 255) String name,
                                     @NotNull LocalDate startDate, @NotNull LocalDate endDate, TripType tripType) {
        public CreatePersonalTrip(String name, LocalDate startDate, LocalDate endDate) { this(name,startDate,endDate,null); }
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported personal-trip field: " + field);
        }
    }
    private final CurrentUser current;
    private final TripRepository trips;
    private final TripMemberRepository members;
    private final ItineraryRepository itineraries;
    private final TripAccessService access;

    public AuthenticatedTripService(CurrentUser current, TripRepository trips,
                                    TripMemberRepository members, ItineraryRepository itineraries,
                                    TripAccessService access) {
        this.current = current;
        this.trips = trips;
        this.members = members;
        this.itineraries = itineraries;
        this.access = access;
    }
    public List<Trip> list() { return trips.findForMember(current.require().getId()); }
    public Trip read(Long id) { return access.requireMember(id, current.require().getId()).getTrip(); }

    @Transactional
    public Trip createForCurrent(CreatePersonalTrip request, Long actorId) {
        if (!actorId.equals(current.require().getId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Owner must be the authenticated actor");
        if (request.endDate().isBefore(request.startDate())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "End date cannot precede start date");
        var trip = new Trip();
        trip.setName(request.name());
        trip.setStartDate(request.startDate());
        trip.setEndDate(request.endDate());
        trip.setTripType(request.tripType() == null ? TripType.SOLO : request.tripType());
        trip.setStatus(TripStatus.PLANNING);
        trips.save(trip);
        var owner = new TripMember();
        owner.setTrip(trip);
        owner.setUser(current.require());
        owner.setRole(TripMemberRole.OWNER);
        members.save(owner);
        var itinerary = new Itinerary();
        itinerary.setTrip(trip);
        itineraries.save(itinerary);
        return trip;
    }

    public Trip updatePlanningFields(Long id, CreatePersonalTrip request) {
        if(request.endDate().isBefore(request.startDate()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"End date cannot precede start date");
        Trip trip=trips.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Trip not found"));
        if(request.tripType()!=null && request.tripType()!=trip.getTripType())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Trip type cannot be changed through a planning update");
        trip.setName(request.name());trip.setStartDate(request.startDate());trip.setEndDate(request.endDate());
        return trips.save(trip);
    }
    @Transactional
    public void deleteEmptyTrip(Long id) {
        // If linked records prevent deletion, foreign-key checks roll back the entire transaction.
        itineraries.findByTrip_Id(id).ifPresent(itineraries::delete);
        itineraries.flush();
        members.deleteAll(members.findByTrip_Id(id));members.flush();trips.deleteById(id);trips.flush();
    }
}
