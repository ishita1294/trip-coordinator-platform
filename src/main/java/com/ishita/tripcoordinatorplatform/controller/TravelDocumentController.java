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
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentController {
    private final com.ishita.tripcoordinatorplatform.auth.DocumentAccessService access;
    private final com.ishita.tripcoordinatorplatform.auth.CoreTripOperations operations;
    private final TravelDocumentUploadService uploadService;
    private final TravelDocumentProcessingService travelDocumentProcessingService;
    private final DocumentConfirmationService documentConfirmationService;

    public TravelDocumentController(
            com.ishita.tripcoordinatorplatform.auth.DocumentAccessService access,
            com.ishita.tripcoordinatorplatform.auth.CoreTripOperations operations,
            TravelDocumentUploadService uploadService,
            TravelDocumentProcessingService travelDocumentProcessingService,
            DocumentConfirmationService documentConfirmationService
    ) {
        this.access=access; this.operations=operations;
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

        Long actor=access.upload(tripId);
        // Pass file details and content to the upload service; keep MultipartFile handling in the controller.
        var content=file.getInputStream();
        return operations.write(tripId, () -> uploadService.uploadDocument(tripId,
                file.getOriginalFilename(),file.getContentType(),file.getSize(),content,actor));
    }

    @GetMapping
    public List<TravelDocumentSummaryResponse> getDocuments(@PathVariable Long tripId) {
        return access.list(tripId);
    }

    @GetMapping("/{documentId}/extractions")
    public List<TravelDocumentExtractionSummaryResponse> getDocumentExtractions(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        access.require(tripId,documentId,false);
        return travelDocumentProcessingService.getDocumentExtractions(tripId, documentId);
    }

    @GetMapping("/{documentId}/extraction")
    public TravelDocumentExtractionResponse getCurrentDocumentExtraction(
            @PathVariable Long tripId,
            @PathVariable Long documentId
    ) {
        access.require(tripId,documentId,false);
        return travelDocumentProcessingService.getCurrentDocumentExtraction(tripId, documentId);
    }

    @GetMapping("/{documentId}/extractions/{extractionId}")
    public TravelDocumentExtractionResponse getDocumentExtraction(
            @PathVariable Long tripId,
            @PathVariable Long documentId,
            @PathVariable Long extractionId
    ) {
        access.require(tripId,documentId,false);
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
        return operations.write(tripId, () -> {
            access.require(tripId,documentId,true);
            return documentConfirmationService.confirm(tripId, documentId, request);
        });
    }

}
