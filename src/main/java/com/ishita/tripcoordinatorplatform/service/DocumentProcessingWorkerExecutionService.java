package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentProcessingBudget;

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
    private final int invalidOutputLimit;

    public DocumentProcessingWorkerExecutionService(TravelDocumentProcessingService processing,
                                                    DocumentProcessingFailureService failures) {
        this(processing, failures, 2);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentProcessingWorkerExecutionService(TravelDocumentProcessingService processing,
            DocumentProcessingFailureService failures,
            @org.springframework.beans.factory.annotation.Value("${app.document-processing.worker.max-invalid-outputs:2}") int invalidOutputLimit) {
        if (invalidOutputLimit < 1) throw new IllegalArgumentException("Invalid-output limit must be positive");
        this.processing = processing;
        this.failures = failures;
        this.invalidOutputLimit = invalidOutputLimit;
    }

    // The consumer calls this only with the token returned by a successful claim.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome execute(Long documentId, UUID attemptId) { return execute(documentId, attemptId, 1, 5); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome execute(Long documentId, UUID attemptId, int receiveCount, int maxDeliveries) {
        if (receiveCount < 1 || maxDeliveries < 1) throw new IllegalArgumentException("Invalid delivery policy");
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        try {
            processing.processClaimedDocument(documentId, attemptId);
            return Outcome.SUCCESS;
        } catch (DocumentProcessingException exception) {
            if (exception.getReason() == DocumentProcessingException.Reason.OWNERSHIP_LOST) {
                return Outcome.OWNERSHIP_LOST;
            }
            return handleFailure(documentId, attemptId, exception, receiveCount >= maxDeliveries, maxDeliveries);
        } catch (RuntimeException exception) {
            return handleFailure(documentId, attemptId, exception, receiveCount >= maxDeliveries, maxDeliveries);
        }
    }

    private Outcome handleFailure(Long documentId, UUID attemptId, RuntimeException failure, boolean exhausted, int maxAttempts) {
        boolean permanent = isPermanentInput(failure);
        boolean invalid = failure instanceof com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException;
        boolean timeout = DocumentProcessingBudget.isTimeout(failure);
        String code = permanent ? "INVALID_SOURCE" : invalid ? "INVALID_EXTRACTION"
                : isExtractionProviderTimeout(failure) ? DocumentProcessingFailureService.EXTRACTION_TIMEOUT : "TECHNICAL_FAILURE";
        // This proxy call commits independently before any retryable exception is thrown.
        DocumentProcessingFailureService.FailureResult result;
        try {
            DocumentProcessingBudget.requireFailurePersistenceTime();
            result = failures.recordFailure(documentId, attemptId, code, permanent || exhausted, invalidOutputLimit, maxAttempts);
            if (result == DocumentProcessingFailureService.FailureResult.OWNERSHIP_LOST) {
                return Outcome.OWNERSHIP_LOST;
            }
        } catch (RuntimeException cleanupFailure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Document failure cleanup unavailable documentId={} cleanupClass={} failureCode={}",
                    documentId, cleanupFailure.getClass().getSimpleName(), code);
            // Provider errors can contain private response bodies; never pass them to Lambda's error logger.
            throw DocumentProcessingException.retryable(new IllegalStateException("Document failure state could not be persisted"));
        }
        // Throw even after FAILED is committed: retain the original failed message in the DLQ.
        // Subsequent deliveries of this failed job perform no AI work and remain unacknowledged.
        org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Document processing failed documentId={} receiveExhausted={} code={} causeClass={} invalidReason={} validationDetail={} timeout={} timeoutReason={}",
                documentId, exhausted, code, failure.getClass().getSimpleName(),
                invalid ? ((com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException) failure).code() : null,
                com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException.validationDetail(failure),
                timeout, failure instanceof DocumentProcessingBudget.ProviderTimeout timed
                        ? timed.reason() + ":" + timed.stage()
                        : failure instanceof DocumentProcessingBudget.PersistenceBudgetExhausted depleted
                        ? "INVOCATION_BUDGET_EXHAUSTED:" + depleted.phase() : timeout ? "PROVIDER_TIMEOUT" : null);
        throw DocumentProcessingException.afterCommittedFailure(result);
    }

    private boolean isExtractionProviderTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DocumentProcessingBudget.ProviderTimeout timed
                    && timed.stage() == DocumentProcessingBudget.Stage.EXTRACTION
                    && "PROVIDER_TIMEOUT".equals(timed.reason())) return true;
            if (cause == cause.getCause()) break;
        }
        return false;
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
