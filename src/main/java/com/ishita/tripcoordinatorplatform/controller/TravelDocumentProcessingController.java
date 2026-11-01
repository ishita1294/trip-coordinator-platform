package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.response.TravelDocumentProcessingResponse;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentProcessingController {

    private final com.ishita.tripcoordinatorplatform.auth.DocumentAccessService access;
    private final com.ishita.tripcoordinatorplatform.auth.CoreTripOperations operations;
    private final DocumentProcessingQueueService queueService;

    public TravelDocumentProcessingController(
            DocumentProcessingQueueService queueService,
            com.ishita.tripcoordinatorplatform.auth.DocumentAccessService access,
            com.ishita.tripcoordinatorplatform.auth.CoreTripOperations operations
    ) {
        this.access=access; this.operations=operations;
        this.queueService = queueService;
    }

    @ExceptionHandler(com.ishita.tripcoordinatorplatform.service.DocumentProcessingPausedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public org.springframework.http.ProblemDetail paused(com.ishita.tripcoordinatorplatform.service.DocumentProcessingPausedException error) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,error.getReason());
    }

    public TravelDocumentProcessingResponse processDocument(Long tripId,Long documentId){return processDocument(tripId,documentId,false);}
    @GetMapping("/processing-options")
    public java.util.Map<String,Boolean> processingOptions(@PathVariable Long tripId) {
        return operations.read(tripId,()->{access.upload(tripId);return java.util.Map.of("sourceBookingReviewEnabled",queueService.sourceBookingReviewEnabled());});
    }
    @PostMapping("/{documentId}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TravelDocumentProcessingResponse processDocument(
            @PathVariable Long tripId,
            @PathVariable Long documentId,
            @RequestParam(defaultValue="false") boolean sourceBookingReview
    ) {
        return operations.write(tripId, () -> {
            access.require(tripId,documentId,true);
            return queueService.queueDocument(tripId,documentId,sourceBookingReview);
        });
    }
}
