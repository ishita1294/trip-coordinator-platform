package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.TripMember;
import com.ishita.tripcoordinatorplatform.model.TripMemberRole;
import com.ishita.tripcoordinatorplatform.service.TripMemberService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class TripMemberController {

    private final TripMemberService tripMemberService;

    public TripMemberController(TripMemberService tripMemberService) {
        this.tripMemberService = tripMemberService;
    }

    @PostMapping("/trips/{tripId}/members")
    public TripMember addMember(
            @PathVariable Long tripId,
            @RequestParam Long userId,
            @RequestParam TripMemberRole role
    ) {
        return tripMemberService.addMember(tripId, userId, role);
    }

    @GetMapping("/trips/{tripId}/members")
    public List<TripMember> getMembers(@PathVariable Long tripId) {
        return tripMemberService.getMembers(tripId);
    }

    @DeleteMapping("/trips/{tripId}/members/{userId}")
    public void removeMember(
            @PathVariable Long tripId,
            @PathVariable Long userId
    ) {


        tripMemberService.removeMember(tripId, userId);
    }
    @PutMapping("/trips/{tripId}/members/{userId}/role")
    public TripMember updateMemberRole(
            @PathVariable Long tripId,
            @PathVariable Long userId,
            @RequestParam TripMemberRole role
    ) {
        return tripMemberService.updateMemberRole(tripId, userId, role);
    }
}
