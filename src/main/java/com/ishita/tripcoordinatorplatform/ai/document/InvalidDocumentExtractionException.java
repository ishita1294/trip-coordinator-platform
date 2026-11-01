package com.ishita.tripcoordinatorplatform.ai.document;

/** Sanitized invalid model evidence; not proof that the source document is invalid. */
public class InvalidDocumentExtractionException extends IllegalArgumentException {
    public enum Code { INVALID_FIELDS, MISSING_BOOKINGS, EMPTY_BOOKINGS, MALFORMED_JSON, EMPTY_OR_REFUSED }
    private final Code code;
    public InvalidDocumentExtractionException(String message) { this(Code.INVALID_FIELDS, message); }
    public InvalidDocumentExtractionException(Code code, String message) { super(message); this.code = code; }
    public InvalidDocumentExtractionException(String message, Throwable cause) { super(message, cause); this.code = Code.INVALID_FIELDS; }
    public Code code() { return code; }

    /** Messages are fixed text, booking indexes and field names only; safe to log. Null for other failures. */
    public static String validationDetail(Throwable failure) {
        return failure instanceof InvalidDocumentExtractionException invalid ? invalid.getMessage() : null;
    }
}
