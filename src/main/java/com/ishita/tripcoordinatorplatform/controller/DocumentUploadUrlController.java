package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.request.DocumentUploadUrlRequest;
import com.ishita.tripcoordinatorplatform.response.DocumentUploadUrlResponse;
import com.ishita.tripcoordinatorplatform.service.DocumentUploadUrlService;
import com.ishita.tripcoordinatorplatform.service.DocumentUploadCompletionService;
import com.ishita.tripcoordinatorplatform.request.CompleteDocumentUploadRequest;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentSummaryResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trips/{tripId}/documents")
@ConditionalOnProperty(name = "app.document-storage.provider", havingValue = "s3")
public class DocumentUploadUrlController {
    private final DocumentUploadUrlService uploads;
    private final DocumentUploadCompletionService completions;

    public DocumentUploadUrlController(DocumentUploadUrlService uploads, DocumentUploadCompletionService completions) {
        this.uploads = uploads;
        this.completions = completions;
    }

    @PostMapping("/complete-upload")
    public TravelDocumentSummaryResponse complete(@PathVariable Long tripId,
                                                @RequestBody CompleteDocumentUploadRequest request) {
        return completions.complete(tripId, request);
    }

    @PostMapping("/upload-url")
    public DocumentUploadUrlResponse initiate(@PathVariable Long tripId,
                                             @RequestBody DocumentUploadUrlRequest request) {
        return uploads.initiate(tripId, request);
    }
}
