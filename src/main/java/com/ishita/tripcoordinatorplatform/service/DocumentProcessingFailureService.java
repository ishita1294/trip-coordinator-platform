package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentProcessingFailureService {

    private final TravelDocumentRepository documents;

    public DocumentProcessingFailureService(TravelDocumentRepository documents) {
        this.documents = documents;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transitionFailure(Long documentId, UUID attemptId, TravelDocumentProcessingStatus status) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        if (status != TravelDocumentProcessingStatus.QUEUED && status != TravelDocumentProcessingStatus.FAILED) {
            throw new IllegalArgumentException("Processing failure status must be QUEUED or FAILED");
        }
        return documents.failProcessingAttempt(documentId, attemptId, status) == 1;
    }
}
