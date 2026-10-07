package com.ishita.tripcoordinatorplatform.service;

import java.util.UUID;

public record DocumentProcessingWorkerClaimResult(Status status, UUID attemptId) {

    public enum Status {
        CLAIMED,
        ALREADY_ACTIVE,
        NOT_PROCESSABLE,
        TERMINAL
    }

    public static final DocumentProcessingWorkerClaimResult ALREADY_ACTIVE =
            new DocumentProcessingWorkerClaimResult(Status.ALREADY_ACTIVE, null);
    public static final DocumentProcessingWorkerClaimResult NOT_PROCESSABLE =
            new DocumentProcessingWorkerClaimResult(Status.NOT_PROCESSABLE, null);
    public static final DocumentProcessingWorkerClaimResult TERMINAL =
            new DocumentProcessingWorkerClaimResult(Status.TERMINAL, null);

    public DocumentProcessingWorkerClaimResult {
        if (status == null || (status == Status.CLAIMED) != (attemptId != null)) {
            throw new IllegalArgumentException("Only a successful claim must carry an attempt ID");
        }
    }
}
