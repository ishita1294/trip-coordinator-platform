package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.openai.errors.OpenAIException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.NoSuchFileException;
import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentProcessingWorkerExecutionService {

    public enum Outcome {
        SUCCESS,
        PERMANENT_FAILURE,
        OWNERSHIP_LOST
    }

    private final TravelDocumentProcessingService processing;
    private final DocumentProcessingFailureService failures;

    public DocumentProcessingWorkerExecutionService(TravelDocumentProcessingService processing,
                                                    DocumentProcessingFailureService failures) {
        this.processing = processing;
        this.failures = failures;
    }

    // A future consumer calls this only with the token returned by a successful claim.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome execute(Long documentId, UUID attemptId) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        try {
            processing.processClaimedDocument(documentId, attemptId);
            return Outcome.SUCCESS;
        } catch (DocumentProcessingException exception) {
            if (exception.getReason() == DocumentProcessingException.Reason.OWNERSHIP_LOST) {
                return Outcome.OWNERSHIP_LOST;
            }
            return handleFailure(documentId, attemptId, exception);
        } catch (RuntimeException exception) {
            return handleFailure(documentId, attemptId, exception);
        }
    }

    private Outcome handleFailure(Long documentId, UUID attemptId, RuntimeException failure) {
        boolean permanent = isPermanentInput(failure);
        var status = permanent ? TravelDocumentProcessingStatus.FAILED : TravelDocumentProcessingStatus.QUEUED;
        // This proxy call commits independently before any retryable exception is thrown.
        try {
            if (!failures.transitionFailure(documentId, attemptId, status)) {
                return Outcome.OWNERSHIP_LOST;
            }
        } catch (RuntimeException cleanupFailure) {
            if (cleanupFailure != failure) {
                cleanupFailure.addSuppressed(failure);
            }
            throw DocumentProcessingException.retryable(cleanupFailure);
        }
        if (permanent) {
            return Outcome.PERMANENT_FAILURE;
        }
        throw DocumentProcessingException.retryable(failure);
    }

    private boolean isPermanentInput(Throwable failure) {
        // Recognize confirmed object absence; a generic S3 404 can instead mean a missing bucket.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoSuchKeyException
                    || (cause instanceof S3Exception s3 && s3.awsErrorDetails() != null
                    && "NoSuchKey".equals(s3.awsErrorDetails().errorCode()))) {
                return true;
            }
            // Credential/configuration failures can themselves wrap missing local credential files.
            if (cause instanceof SdkException || cause instanceof OpenAIException) {
                return false;
            }
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoSuchFileException
                    || (cause instanceof DocumentProcessingException processingFailure
                    && processingFailure.getReason() == DocumentProcessingException.Reason.PERMANENT_INPUT)) {
                return true;
            }
        }
        // Invalid AI output and unclassified errors are technical failures, never presumed bad documents.
        return false;
    }
}
