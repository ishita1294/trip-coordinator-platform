package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.*;
import com.ishita.tripcoordinatorplatform.service.TourBookingStateService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/documents/{documentId}/booking-state")
public class TourBookingStateController {
    private final DocumentAccessService access;
    private final CurrentUser current;
    private final TourBookingStateService state;
    public TourBookingStateController(DocumentAccessService access,CurrentUser current,TourBookingStateService state){this.access=access;this.current=current;this.state=state;}
    @GetMapping public TourBookingStateService.Snapshot get(@PathVariable Long tripId,@PathVariable Long documentId){
        access.require(tripId,documentId,false);return state.snapshot(tripId,documentId,current.require().getId());
    }
}
