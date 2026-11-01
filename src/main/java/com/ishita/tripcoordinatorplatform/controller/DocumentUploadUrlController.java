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
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/documents")
@ConditionalOnProperty(name = "app.document-storage.provider", havingValue = "s3")
public class DocumentUploadUrlController {
    private final com.ishita.tripcoordinatorplatform.auth.DocumentAccessService access;
    private final com.ishita.tripcoordinatorplatform.service.DocumentDownloadUrlService downloads;
    private final com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository documents;
    private final DocumentUploadUrlService uploads;
    private final DocumentUploadCompletionService completions;

    public DocumentUploadUrlController(DocumentUploadUrlService uploads, DocumentUploadCompletionService completions, com.ishita.tripcoordinatorplatform.auth.DocumentAccessService access, com.ishita.tripcoordinatorplatform.service.DocumentDownloadUrlService downloads, com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository documents) {
        this.access=access;this.downloads=downloads;this.documents=documents;
        this.uploads = uploads;
        this.completions = completions;
    }

    @GetMapping("/{documentId}/download-url")
    public com.ishita.tripcoordinatorplatform.service.DocumentDownloadUrlService.Download download(
            @PathVariable Long tripId, @PathVariable Long documentId) {
        access.require(tripId,documentId,false);
        return downloads.issue(documents.findByIdAndTrip_Id(documentId,tripId).orElseThrow().getStorageKey());
    }

    @PostMapping("/complete-upload")
    public TravelDocumentSummaryResponse complete(@PathVariable Long tripId,
                                                @RequestBody CompleteDocumentUploadRequest request) {
        return completions.complete(tripId, request, access.upload(tripId));
    }

    @PostMapping("/upload-url")
    public DocumentUploadUrlResponse initiate(@PathVariable Long tripId,
                                             @RequestBody DocumentUploadUrlRequest request) {
        return uploads.initiate(tripId, request, access.upload(tripId));
    }
}
