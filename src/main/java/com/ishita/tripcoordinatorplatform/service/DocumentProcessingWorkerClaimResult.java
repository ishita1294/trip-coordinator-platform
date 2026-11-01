package com.ishita.tripcoordinatorplatform.service;

import java.util.UUID;

public record DocumentProcessingWorkerClaimResult(Status status, UUID attemptId, java.time.Instant leaseExpiresAt) {

    public DocumentProcessingWorkerClaimResult(Status status, UUID attemptId) { this(status, attemptId, null); }

    public enum Status {
        CLAIMED,
        ALREADY_ACTIVE,
        NOT_PROCESSABLE,
        TERMINAL,
        TERMINAL_FAILED
    }

    public static final DocumentProcessingWorkerClaimResult ALREADY_ACTIVE =
            new DocumentProcessingWorkerClaimResult(Status.ALREADY_ACTIVE, null);
    public static final DocumentProcessingWorkerClaimResult NOT_PROCESSABLE =
            new DocumentProcessingWorkerClaimResult(Status.NOT_PROCESSABLE, null);
    public static final DocumentProcessingWorkerClaimResult TERMINAL =
            new DocumentProcessingWorkerClaimResult(Status.TERMINAL, null);

    public static final DocumentProcessingWorkerClaimResult TERMINAL_FAILED = new DocumentProcessingWorkerClaimResult(Status.TERMINAL_FAILED, null);

    public DocumentProcessingWorkerClaimResult {
        if (leaseExpiresAt != null && status != Status.CLAIMED && status != Status.ALREADY_ACTIVE)
            throw new IllegalArgumentException("Only claimed or active work has a lease expiry");
        if (status == null || (status == Status.CLAIMED) != (attemptId != null)) {
            throw new IllegalArgumentException("Only a successful claim must carry an attempt ID");
        }
    }
}
