package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable assessment payload; acceptance is a single versioned transition. */
@Entity @Table(name="tour_correction_reviews",uniqueConstraints=@UniqueConstraint(columnNames={"document_id","actor_id","request_id"}))
public class TourCorrectionReview {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long getId(){return id;}
    @Column(name="document_id",nullable=false,updatable=false) public Long documentId;
    @Column(name="extraction_id",nullable=false,updatable=false) public Long extractionId;
    @Column(name="actor_id",nullable=false,updatable=false) public Long actorId;
    @Column(name="request_id",nullable=false,updatable=false) public UUID requestId;
    @Column(name="extraction_sha256",nullable=false,updatable=false,length=64) public String extractionSha256;
    @Column(name="document_sha256",nullable=false,updatable=false,length=64) public String documentSha256;
    @Column(name="changes_json",nullable=false,updatable=false,columnDefinition="text") public String changesJson;
    @Column(name="assessment_json",nullable=false,updatable=false,columnDefinition="text") public String assessmentJson;
    @Column(name="created_at",nullable=false,updatable=false) public Instant createdAt;
    @Column(name="accepted_at") public Instant acceptedAt;
    @Column(name="accepted_by") public Long acceptedBy;
    @Version @Column(nullable=false) public long version;
    /** Response-only deterministic preview; durable provenance remains in immutable changes/source/assessment. */
    @Transient public com.ishita.tripcoordinatorplatform.response.TourBookingPreview effectivePreview;
}
