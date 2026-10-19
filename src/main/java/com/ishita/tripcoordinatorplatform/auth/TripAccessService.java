package com.ishita.tripcoordinatorplatform.auth;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.TripMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** Checks whether a user belongs to a trip and has the required role. The acting user ID must come from CurrentUser, not a requested participant ID. */
@Service
public class TripAccessService {
    private final TripMemberRepository members;
    public TripAccessService(TripMemberRepository members) { this.members = members; }

    /** Checks trip membership without loading the trip, user or their related records. */
    public void requireMembership(Long tripId, Long actorId) {
        if (!members.existsByTrip_IdAndUser_Id(tripId, actorId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found");
    }

    public TripMember requireMember(Long tripId, Long actorId) {
        return members.findByTrip_IdAndUser_Id(tripId, actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
    }
    public TripMember requireEditor(Long tripId, Long actorId) {
        return requireRoles(tripId, actorId, Set.of(TripMemberRole.OWNER, TripMemberRole.EDITOR));
    }
    public TripMember requireOwner(Long tripId, Long actorId) {
        return requireRoles(tripId, actorId, Set.of(TripMemberRole.OWNER));
    }
    private TripMember requireRoles(Long tripId, Long actorId, Set<TripMemberRole> allowed) {
        var member = requireMember(tripId, actorId);
        if (!allowed.contains(member.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Trip role does not permit this action");
        return member;
    }
}
