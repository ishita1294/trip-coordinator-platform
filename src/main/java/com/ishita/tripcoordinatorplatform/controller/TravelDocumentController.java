package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.request.ConfirmFlightDocumentRequest;
import com.ishita.tripcoordinatorplatform.response.ConfirmFlightDocumentResponse;
import com.ishita.tripcoordinatorplatform.service.FlightDocumentConfirmationService;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentExtractionResponse;
import com.ishita.tripcoordinatorplatform.service.TravelDocumentProcessingService;
import com.ishita.tripcoordinatorplatform.service.TravelDocumentUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentController {
    private final TravelDocumentUploadService uploadService;
    private final TravelDocumentProcessingService travelDocumentProcessingService;
    private final FlightDocumentConfirmationService flightDocumentConfirmationService;

    public TravelDocumentController(
            TravelDocumentUploadService uploadService,
            TravelDocumentProcessingService travelDocumentProcessingService,
            FlightDocumentConfirmationService flightDocumentConfirmationService
    ) {
        this.uploadService = uploadService;
        this.travelDocumentProcessingService = travelDocumentProcessingService;
        this.flightDocumentConfirmationService = flightDocumentConfirmationService;
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

    @PostMapping("/{documentId}/extractions/{extractionId}/confirm")
    public ConfirmFlightDocumentResponse confirmFlightDocument(
            @PathVariable Long tripId,
            @PathVariable Long documentId,
            @PathVariable Long extractionId,
            @RequestBody ConfirmFlightDocumentRequest request
    ) {
        return flightDocumentConfirmationService.confirm(tripId, documentId, extractionId, request);
    }

}
