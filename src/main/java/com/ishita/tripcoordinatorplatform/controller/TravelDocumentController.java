package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.request.ConfirmAttractionTourDocumentRequest;
import com.ishita.tripcoordinatorplatform.response.ConfirmDocumentResponse;
import com.ishita.tripcoordinatorplatform.service.DocumentConfirmationService;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentExtractionResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentExtractionSummaryResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentSummaryResponse;
import com.ishita.tripcoordinatorplatform.service.TravelDocumentProcessingService;
import com.ishita.tripcoordinatorplatform.service.TravelDocumentUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentController {
    private final TravelDocumentUploadService uploadService;
    private final TravelDocumentProcessingService travelDocumentProcessingService;
    private final DocumentConfirmationService documentConfirmationService;

    public TravelDocumentController(
            TravelDocumentUploadService uploadService,
            TravelDocumentProcessingService travelDocumentProcessingService,
            DocumentConfirmationService documentConfirmationService
    ) {
        this.uploadService = uploadService;
        this.travelDocumentProcessingService = travelDocumentProcessingService;
        this.documentConfirmationService = documentConfirmationService;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public TravelDocument uploadDocument(
            @PathVariable Long tripId,
            @RequestParam("file") MultipartFile file
    ) throws IOException {

        // The controller handles the HTTP-specific MultipartFile and passes only
        // generic file information/content into the application service.
        return uploadService.uploadDocument(
                tripId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream()
        );
    }

    @GetMapping
    public List<TravelDocumentSummaryResponse> getDocuments(@PathVariable Long tripId) {
        return travelDocumentProcessingService.getDocuments(tripId);
    }

    @GetMapping("/{documentId}/extractions")
    public List<TravelDocumentExtractionSummaryResponse> getDocumentExtractions(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        return travelDocumentProcessingService.getDocumentExtractions(tripId, documentId);
    }

    @GetMapping("/{documentId}/extraction")
    public TravelDocumentExtractionResponse getCurrentDocumentExtraction(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        return travelDocumentProcessingService.getCurrentDocumentExtraction(tripId, documentId);
    }

    @GetMapping("/{documentId}/extractions/{extractionId}")
    public TravelDocumentExtractionResponse getDocumentExtraction(
            @PathVariable Long tripId,
            @PathVariable Long documentId,
            @PathVariable Long extractionId
    ) {
        return travelDocumentProcessingService.getDocumentExtraction(
                tripId,
                documentId,
                extractionId
        );
    }

    @PostMapping("/{documentId}/confirm")
    public ConfirmDocumentResponse confirmCurrentDocument(
            @PathVariable Long tripId,
            @PathVariable Long documentId,
            @RequestBody(required = false) ConfirmAttractionTourDocumentRequest request
    ) {
        return documentConfirmationService.confirm(tripId, documentId, request);
    }

}
