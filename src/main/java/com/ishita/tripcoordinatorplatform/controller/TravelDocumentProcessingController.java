package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.service.TravelDocumentProcessingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentProcessingController {

    private final TravelDocumentProcessingService processingService;

    public TravelDocumentProcessingController(
            TravelDocumentProcessingService processingService
    ) {
        this.processingService = processingService;
    }

    @PostMapping("/{documentId}/process")
    public TravelDocument processDocument(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        // Temporary local-development trigger. Later, asynchronous processing
        // such as SQS will start this service instead of an HTTP endpoint.
        return processingService.classifyDocument(
                tripId,
                documentId
        );
    }
}
