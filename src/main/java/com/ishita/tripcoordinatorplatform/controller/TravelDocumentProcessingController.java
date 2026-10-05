package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.response.TravelDocumentProcessingResponse;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingQueueService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentProcessingController {

    private final DocumentProcessingQueueService queueService;

    public TravelDocumentProcessingController(
            DocumentProcessingQueueService queueService
    ) {
        this.queueService = queueService;
    }

    @PostMapping("/{documentId}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TravelDocumentProcessingResponse processDocument(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        return queueService.queueDocument(
                tripId,
                documentId
        );
    }
}
