package com.ishita.tripcoordinatorplatform.storage;

/** Typed storage failure, retaining causes for existing worker retry classification. */
public final class DocumentReadException extends IllegalStateException {
    public enum Reason { MISSING, ACCESS_DENIED, UNAVAILABLE }
    private final Reason reason;
    public DocumentReadException(Reason reason,Throwable cause){super("Original document could not be read",cause);this.reason=reason;}
    public Reason reason(){return reason;}
}
