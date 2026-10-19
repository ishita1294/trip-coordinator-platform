package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.TripMember;
import com.ishita.tripcoordinatorplatform.model.TripMemberRole;
import com.ishita.tripcoordinatorplatform.service.TripMemberService;
import com.ishita.tripcoordinatorplatform.auth.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class TripMemberController {

    private final TripMemberService tripMemberService;
    private final CurrentUser currentUser;

    public TripMemberController(TripMemberService tripMemberService, CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.tripMemberService = tripMemberService;
    }

    @PostMapping("/trips/{tripId}/members")
    public TripMember addMember(
            @PathVariable Long tripId,
            @RequestParam Long userId,
            @RequestParam TripMemberRole role
    ) {
        currentUser.require();
        return tripMemberService.addMember(tripId, userId, role);
    }

    @GetMapping("/trips/{tripId}/members")
    public List<TripMember> getMembers(@PathVariable Long tripId) {
        currentUser.require();
        return tripMemberService.getMembers(tripId);
    }

    @DeleteMapping("/trips/{tripId}/members/{userId}")
    public void removeMember(
            @PathVariable Long tripId,
            @PathVariable Long userId
    ) {
        currentUser.require();
        tripMemberService.removeMember(tripId, userId);
    }
    @PutMapping("/trips/{tripId}/members/{userId}/role")
    public TripMember updateMemberRole(
            @PathVariable Long tripId,
            @PathVariable Long userId,
            @RequestParam TripMemberRole role
    ) {
        currentUser.require();
        return tripMemberService.updateMemberRole(tripId, userId, role);
    }
}
