package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.service.TravelDocumentUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/trips/{tripId}/documents")
public class TravelDocumentController {
    private final TravelDocumentUploadService uploadService;

    public TravelDocumentController(
            TravelDocumentUploadService uploadService
    ) {
        this.uploadService = uploadService;
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

}
