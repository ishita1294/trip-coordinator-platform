package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable confirmation occurrence, not an active reservation or a lifecycle decision. */
@Entity
@Table(name="booking_confirmation_receipts",uniqueConstraints=@UniqueConstraint(columnNames={"extraction_id","original_booking_index"}))
@org.hibernate.annotations.Immutable
public class BookingConfirmationReceipt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="extraction_id",nullable=false,updatable=false) private Long extractionId;
    @Column(name="original_booking_index",nullable=false,updatable=false) private int originalBookingIndex;
    @Column(name="confirmed_by",nullable=false,updatable=false) private Long confirmedBy;
    @Column(name="confirmed_at",nullable=false,updatable=false) private Instant confirmedAt;
    @Column(name="reservation_id",nullable=false,updatable=false) private Long reservationId;
    @Column(name="extraction_sha256",nullable=false,updatable=false,length=64) private String extractionSha256;
    @Column(name="schema_version",nullable=false,updatable=false) private int schemaVersion;
    @Column(name="prompt_version",updatable=false,length=100) private String promptVersion;
    @Column(name="accepted_review_id",updatable=false) private Long acceptedReviewId;
    @Column(name="accepted_review_version",updatable=false) private Long acceptedReviewVersion;
    protected BookingConfirmationReceipt() {}
    public BookingConfirmationReceipt(Long extractionId,int originalBookingIndex,Long actor,Instant at,Long reservationId,
            String extractionSha256,int schemaVersion,String promptVersion,Long acceptedReviewId,Long acceptedReviewVersion) {
        this.extractionId=extractionId;this.originalBookingIndex=originalBookingIndex;this.confirmedBy=actor;this.confirmedAt=at;
        this.reservationId=reservationId;this.extractionSha256=extractionSha256;this.schemaVersion=schemaVersion;
        this.promptVersion=promptVersion;this.acceptedReviewId=acceptedReviewId;this.acceptedReviewVersion=acceptedReviewVersion;
    }
    public Long getId(){return id;}
    public Long getExtractionId(){return extractionId;}
    public int getOriginalBookingIndex(){return originalBookingIndex;}
    public Long getConfirmedBy(){return confirmedBy;}
    public Instant getConfirmedAt(){return confirmedAt;}
    public Long getReservationId(){return reservationId;}
    public String getExtractionSha256(){return extractionSha256;}
    public int getSchemaVersion(){return schemaVersion;}
    public String getPromptVersion(){return promptVersion;}
    public Long getAcceptedReviewId(){return acceptedReviewId;}
    public Long getAcceptedReviewVersion(){return acceptedReviewVersion;}
}
