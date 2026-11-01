package com.ishita.tripcoordinatorplatform.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DocumentProcessingPausedException extends ResponseStatusException {
    public DocumentProcessingPausedException() {
        super(HttpStatus.SERVICE_UNAVAILABLE,"Document processing is paused. This request did not queue or change the document. An operator must enable processing before it can start.");
    }
}
