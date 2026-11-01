package com.ishita.tripcoordinatorplatform.ai.document;

/** Known incomplete/malformed provider assessment; not a catch-all for programming errors. */
public final class SourceAssessmentUnavailableException extends IllegalStateException {
    public SourceAssessmentUnavailableException(){super("Source assessment unavailable");}
}
