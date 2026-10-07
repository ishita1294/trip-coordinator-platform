package com.ishita.tripcoordinatorplatform.model;


import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "travel_documents")
public class TravelDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false, unique = true)
    private String storageKey;

    @Column(nullable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TravelDocumentProcessingStatus processingStatus;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_attempt_id")
    private UUID processingAttemptId;

    public UUID getProcessingAttemptId() {
        return processingAttemptId;
    }

    public void setProcessingAttemptId(UUID processingAttemptId) {
        this.processingAttemptId = processingAttemptId;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(Instant processingStartedAt) {
        this.processingStartedAt = processingStartedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public TravelDocumentProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(TravelDocumentProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public TravelDocumentType getDocumentType() {
        return documentType;
    }

    public void setDocumentType(TravelDocumentType documentType) {
        this.documentType = documentType;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    @Enumerated(EnumType.STRING)
    private TravelDocumentType documentType;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt;
}
