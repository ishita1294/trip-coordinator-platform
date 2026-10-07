package com.ishita.tripcoordinatorplatform.service;

public class DocumentProcessingException extends IllegalStateException {

    public enum Reason {
        RETRYABLE,
        PERMANENT_INPUT,
        OWNERSHIP_LOST
    }

    private final Reason reason;

    private DocumentProcessingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public static DocumentProcessingException retryable(Throwable cause) {
        return new DocumentProcessingException(Reason.RETRYABLE, "Document processing failed; retry required", cause);
    }

    public static DocumentProcessingException permanentInput(String message) {
        return new DocumentProcessingException(Reason.PERMANENT_INPUT, message, null);
    }

    public static DocumentProcessingException ownershipLost() {
        return new DocumentProcessingException(Reason.OWNERSHIP_LOST,
                "Document processing attempt no longer owns the document", null);
    }
}
