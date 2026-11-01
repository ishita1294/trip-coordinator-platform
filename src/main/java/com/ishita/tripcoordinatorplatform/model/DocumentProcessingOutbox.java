package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "document_processing_outbox")
public class DocumentProcessingOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentProcessingOutboxStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;
    @Column(name = "extraction_contract", updatable = false, length = 32)
    private String extractionContract;
    public String getExtractionContract() { return extractionContract; }
    public void setExtractionContract(String value) { extractionContract = value; }

    @Column(updatable = false)
    private java.util.UUID processingJobId;
    @Column(updatable = false)
    private Instant processingFailedAt;
    public Instant getProcessingFailedAt() { return processingFailedAt; }
    public java.util.UUID getProcessingJobId() { return processingJobId; }
    public void setProcessingJobId(java.util.UUID value) { processingJobId = value; }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public DocumentProcessingOutboxStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentProcessingOutboxStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
