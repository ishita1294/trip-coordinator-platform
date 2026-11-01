package com.ishita.tripcoordinatorplatform.service;

public class DocumentProcessingException extends IllegalStateException {

    public enum Reason {
        RETRYABLE,
        PERMANENT_INPUT,
        OWNERSHIP_LOST
    }

    private final Reason reason;
    private final DocumentProcessingFailureService.FailureResult committedFailure;

    private DocumentProcessingException(Reason reason, String message, Throwable cause) {
        this(reason, message, cause, null);
    }

    private DocumentProcessingException(Reason reason, String message, Throwable cause,
            DocumentProcessingFailureService.FailureResult committedFailure) {
        super(message, cause);
        this.reason = reason;
        this.committedFailure = committedFailure;
    }

    public Reason getReason() {
        return reason;
    }

    public static DocumentProcessingException retryable(Throwable cause) {
        return new DocumentProcessingException(Reason.RETRYABLE, "Document processing failed; retry required", cause);
    }

    /** Only the worker calls this after the separate failure transaction has committed. */
    public static DocumentProcessingException afterCommittedFailure(DocumentProcessingFailureService.FailureResult result) {
        if (result != DocumentProcessingFailureService.FailureResult.RETRY
                && result != DocumentProcessingFailureService.FailureResult.FAILED)
            throw new IllegalArgumentException("Expected a committed retryable or terminal failure");
        return new DocumentProcessingException(Reason.RETRYABLE, "Document processing failure was persisted", null, result);
    }

    public DocumentProcessingFailureService.FailureResult getCommittedFailure() { return committedFailure; }

    public static DocumentProcessingException permanentInput(String message) {
        return new DocumentProcessingException(Reason.PERMANENT_INPUT, message, null);
    }

    public static DocumentProcessingException ownershipLost() {
        return new DocumentProcessingException(Reason.OWNERSHIP_LOST,
                "Document processing attempt no longer owns the document", null);
    }
}
