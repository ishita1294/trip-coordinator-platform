package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.*;
import com.ishita.tripcoordinatorplatform.model.TourCorrectionReview;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest;
import com.ishita.tripcoordinatorplatform.service.TourCorrectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/documents/{documentId}/correction-reviews")
public class TourCorrectionController {
    private final CoreTripOperations operations;
    private final DocumentAccessService access;
    private final TourCorrectionService corrections;
    private final CurrentUser current;
    public TourCorrectionController(CoreTripOperations operations,DocumentAccessService access,TourCorrectionService corrections,CurrentUser current){this.operations=operations;this.access=access;this.corrections=corrections;this.current=current;}
    @GetMapping public java.util.List<TourCorrectionReview> history(@PathVariable Long tripId,@PathVariable Long documentId){
        access.require(tripId,documentId,false);
        return corrections.history(tripId,documentId,current.require().getId());
    }
    @PostMapping public TourCorrectionReview assess(@PathVariable Long tripId,@PathVariable Long documentId,@RequestBody TourCorrectionRequest request){
        return operations.write(tripId,()->{
            access.require(tripId,documentId,true);
            return corrections.assess(tripId,documentId,access.upload(tripId),request);
        });
    }
    @PostMapping("/{reviewId}/accept-lifecycle")
    public com.ishita.tripcoordinatorplatform.service.TourBookingStateService.Snapshot acceptLifecycle(@PathVariable Long tripId,@PathVariable Long documentId,@PathVariable Long reviewId,@RequestBody TourCorrectionService.LifecycleAcceptance request){
        return operations.write(tripId,()->{access.require(tripId,documentId,true);return corrections.acceptLifecycle(tripId,documentId,access.upload(tripId),reviewId,request);});
    }

}
