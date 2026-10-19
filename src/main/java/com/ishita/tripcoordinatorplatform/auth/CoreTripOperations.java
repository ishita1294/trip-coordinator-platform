package com.ishita.tripcoordinatorplatform.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import java.util.function.Supplier;


/** Checks trip permissions before running HTTP requests against the existing services. Writes lock the trip and check the current role within the same transaction. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CoreTripOperations {
    private final CurrentUser current;
    private final TripAccessService access;
    private final TripRepository trips;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public CoreTripOperations(CurrentUser current, TripAccessService access, TripRepository trips,
                              JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.current=current;this.access=access;this.trips=trips;this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager);
    }
    public <T> T read(Long tripId, Supplier<T> work) {
        access.requireMember(tripId,current.require().getId());
        return work.get();
    }
    public <T> T write(Long tripId, Supplier<T> work) { return mutate(tripId,false,work); }
    public <T> T ownerWrite(Long tripId, Supplier<T> work) { return mutate(tripId,true,work); }
    private <T> T mutate(Long tripId, boolean owner, Supplier<T> work) {
        Long actor=current.require().getId(); // Resolve or create the local user before opening the trip transaction.
        try {
            return transaction.execute(status -> {
                trips.lockForMembershipChange(tripId).orElseThrow(CoreTripOperations::missing);
                if(owner)access.requireOwner(tripId,actor);else access.requireEditor(tripId,actor);
                return work.get();
            });
        } catch(DataIntegrityViolationException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Operation conflicts with existing linked records; resolve those references first");
        }
    }
    /** Checks that referenced records belong to this trip without loading entities from another trip or caching membership state before a write lock. */
    public void destination(Long tripId,Long id) { exists("select count(*) from destinations where id=? and trip_id=?",id,tripId); }
    public void reservation(Long tripId,Long id) { exists("select count(*) from reservations where id=? and trip_id=?",id,tripId); }
    public void item(Long tripId,Long id) { exists("select count(*) from itinerary_items i join itineraries t on t.id=i.itinerary_id where i.id=? and t.trip_id=?",id,tripId); }
    public void member(Long tripId,Long id) { if(id==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Participant ID is required"); exists("select count(*) from trip_members where id=? and trip_id=?",id,tripId); }
    public void activity(Long tripId,Long id) { exists("select count(*) from activities where id=? and trip_id=?",id,tripId); }
    public void activityAt(Long tripId,Long destinationId,Long id) {
        if(id!=null && !Integer.valueOf(1).equals(jdbc.queryForObject("select count(*) from activities where id=? and trip_id=? and destination_id=?",Integer.class,id,tripId,destinationId)))throw missing();
    }
    public void conflict(Long tripId,Long memberId,Long id) {
        if(!Integer.valueOf(1).equals(jdbc.queryForObject("select count(*) from itinerary_conflicts c join itinerary_items a on a.id=c.first_item_id join itineraries i on i.id=a.itinerary_id join itinerary_items b on b.id=c.second_item_id join itineraries j on j.id=b.itinerary_id where c.id=? and c.trip_member_id=? and i.trip_id=? and j.trip_id=?",Integer.class,id,memberId,tripId,tripId)))throw missing();
    }
    private void exists(String sql,Long id,Long tripId) {
        if(id!=null && !Integer.valueOf(1).equals(jdbc.queryForObject(sql,Integer.class,id,tripId)))throw missing();
    }
    public void newEntity(Long id) {
        if(id!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Creation cannot supply an existing resource ID");
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found for this trip"); }
}
