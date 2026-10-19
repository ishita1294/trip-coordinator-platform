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

    @PostMapping("/{documentId}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TravelDocumentProcessingResponse processDocument(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        return operations.write(tripId, () -> {
            access.require(tripId,documentId,true);
            return queueService.queueDocument(tripId,documentId);
        });
    }
}
