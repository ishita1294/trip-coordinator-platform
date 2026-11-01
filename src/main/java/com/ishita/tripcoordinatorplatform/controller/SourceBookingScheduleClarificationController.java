package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.auth.*;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput;
import com.ishita.tripcoordinatorplatform.service.*;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

/** Authorized native schedule declaration only: no confirmation, source reprocessing or provider route. */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/documents/{documentId}/native-schedule-clarifications")
public class SourceBookingScheduleClarificationController {
    private final CoreTripOperations operations;private final DocumentAccessService access;
    private final SourceBookingCorrectionService corrections;private final DocumentStorage storage;
    private final com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository documents;
    public SourceBookingScheduleClarificationController(CoreTripOperations operations,DocumentAccessService access,
            SourceBookingCorrectionService corrections,DocumentStorage storage,com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository documents) {
        this.operations=operations;this.access=access;this.corrections=corrections;this.storage=storage;this.documents=documents;
    }
    private DocumentAiInput original(Long trip,Long document) {
        access.require(trip,document,false);
        var doc=documents.findByIdAndTrip_Id(document,trip).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        try(var input=storage.open(doc.getStorageKey())) {
            var bytes=input.readNBytes(20*1024*1024+1);
            if(bytes.length==0 || bytes.length>20*1024*1024)throw TourCorrectionException.invalid("Original source unavailable or oversized.");
            return new DocumentAiInput(doc.getOriginalFileName(),doc.getContentType(),bytes);
        }catch(java.io.IOException failure){throw TourCorrectionException.invalid("Original source unavailable.");}
    }
    @GetMapping public SourceBookingAcceptedReviewService.Review current(@PathVariable Long tripId,@PathVariable Long documentId) {
        return operations.read(tripId,()->{access.require(tripId,documentId,false);return corrections.scheduleState(tripId,documentId,access.upload(tripId),original(tripId,documentId));});
    }
    @PostMapping public com.ishita.tripcoordinatorplatform.model.TourCorrectionReview assess(@PathVariable Long tripId,@PathVariable Long documentId,
            @RequestBody SourceBookingCorrectionService.ScheduleRequest request) {
        return operations.write(tripId,()->{access.require(tripId,documentId,true);return corrections.assessSchedule(tripId,documentId,access.upload(tripId),request,original(tripId,documentId));});
    }
    @PostMapping("/{reviewId}/accept") public SourceBookingAcceptedReviewService.Review accept(@PathVariable Long tripId,@PathVariable Long documentId,
            @PathVariable Long reviewId,@RequestBody SourceBookingCorrectionService.Acceptance request) {
        return operations.write(tripId,()->{access.require(tripId,documentId,true);return corrections.acceptSchedule(tripId,documentId,access.upload(tripId),reviewId,request,original(tripId,documentId));});
    }
}
