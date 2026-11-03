package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.*;
import com.ishita.tripcoordinatorplatform.intelligence.planning.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/explore/hours")
public class ExploreHoursController {
    private final CurrentUser current;private final TripAccessService access;private final ExplorationHoursStore store;
    public ExploreHoursController(CurrentUser current,TripAccessService access,ExplorationHoursStore store){this.current=current;this.access=access;this.store=store;}
    @GetMapping public ResponseEntity<ExplorationHoursStore.Saved> read(@PathVariable Long tripId){Long actor=current.require().getId();access.requireMembership(tripId,actor);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.read(tripId,actor));}
    @PutMapping public ResponseEntity<ExplorationHoursStore.Saved> save(@PathVariable Long tripId,@RequestBody ExplorationHoursStore.Saved request){Long actor=current.require().getId();access.requireMembership(tripId,actor);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.save(tripId,actor,request));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage()));}
}
