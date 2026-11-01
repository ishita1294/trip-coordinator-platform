package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;

public interface TravelDocumentRepository extends JpaRepository<TravelDocument, Long> {
    boolean existsByIdAndTrip_IdAndUploadedByUserId(Long id, Long tripId, Long actorId);
    java.util.List<TravelDocument> findAllByTrip_IdAndUploadedByUserIdOrderByUploadedAtDescIdDesc(Long tripId, Long actorId);


    Optional<TravelDocument> findByStorageKey(String storageKey);

    @Query("SELECT document.documentType FROM TravelDocument document WHERE document.id = :documentId AND document.trip.id = :tripId")
    Optional<TravelDocumentType> findDocumentTypeForDispatch(@Param("documentId") Long documentId,
                                                           @Param("tripId") Long tripId);

    boolean existsByIdAndTrip_Id(Long documentId, Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT document FROM TravelDocument document WHERE document.id = :documentId AND document.trip.id = :tripId")
    Optional<TravelDocument> findForConfirmation(@Param("documentId") Long documentId,
                                                @Param("tripId") Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT document FROM TravelDocument document WHERE document.id = :documentId")
    Optional<TravelDocument> findForProcessingFailure(@Param("documentId") Long documentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStatus = PROCESSING,
                document.processingStartedAt = :now,
                document.processingDeliveryCount = CASE WHEN document.processingDeliveryCount > :receiveCount
                    THEN document.processingDeliveryCount ELSE :receiveCount END,
                document.processingAttemptCount = document.processingAttemptCount + 1,
                document.processingAttemptId = :attemptId
            WHERE document.id = :documentId
              AND document.processingStatus = QUEUED
              AND ((:jobId IS NULL AND document.processingJobId IS NULL) OR document.processingJobId = :jobId)
              AND document.processingAttemptCount < :maxAttempts
              AND document.processingDeliveryCount < :maxAttempts
            """)
    int claimQueuedForProcessing(@Param("documentId") Long documentId, @Param("now") Instant now,
                                 @Param("attemptId") UUID attemptId, @Param("jobId") UUID jobId, @Param("receiveCount") int receiveCount, @Param("maxAttempts") int maxAttempts);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStartedAt = :now,
                document.processingDeliveryCount = CASE WHEN document.processingDeliveryCount > :receiveCount
                    THEN document.processingDeliveryCount ELSE :receiveCount END,
                document.processingAttemptCount = document.processingAttemptCount + 1,
                document.processingAttemptId = :attemptId
            WHERE document.id = :documentId
              AND document.processingStatus = PROCESSING
              AND document.processingStartedAt <= :staleBefore
              AND ((:jobId IS NULL AND document.processingJobId IS NULL) OR document.processingJobId = :jobId)
              AND document.processingAttemptCount < :maxAttempts
              AND document.processingDeliveryCount < :maxAttempts
            """)
    int reclaimStaleProcessing(@Param("documentId") Long documentId,
                               @Param("now") Instant now,
                               @Param("staleBefore") Instant staleBefore,
                               @Param("attemptId") UUID attemptId, @Param("jobId") UUID jobId, @Param("receiveCount") int receiveCount, @Param("maxAttempts") int maxAttempts);

    // Duplicate deliveries count toward SQS redrive even when another attempt still owns the work.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document SET document.processingDeliveryCount = :receiveCount
            WHERE document.id = :documentId AND document.processingStatus = PROCESSING
              AND ((:jobId IS NULL AND document.processingJobId IS NULL) OR document.processingJobId = :jobId)
              AND document.processingDeliveryCount < :receiveCount
            """)
    int observeActiveDelivery(@Param("documentId") Long documentId, @Param("jobId") UUID jobId,
                              @Param("receiveCount") int receiveCount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStatus = REVIEW_REQUIRED,
                document.documentType = :documentType,
                document.processingStartedAt = NULL,
                document.processingAttemptId = NULL
            WHERE document.id = :documentId
              AND document.processingStatus = PROCESSING
              AND document.processingAttemptId = :attemptId
            """)
    int completeProcessingAttempt(@Param("documentId") Long documentId,
                                  @Param("attemptId") UUID attemptId,
                                  @Param("documentType") TravelDocumentType documentType);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStatus = :queuedStatus,
                document.processingJobId = :jobId,
                document.processingFailureCount = 0,
                document.processingDeliveryCount = 0,
                document.processingAttemptCount = 0,
                document.processingInvalidOutputCount = 0,
                document.processingExtractionTimeoutCount = 0,
                document.processingLastErrorCode = NULL,
                document.processingFailedAt = NULL,
                document.processingStartedAt = NULL,
                document.processingAttemptId = NULL
            WHERE document.id = :documentId
              AND document.trip.id = :tripId
              AND document.processingStatus IN :allowedStatuses
            """)
    int claimForQueue(
            @Param("documentId") Long documentId,
            @Param("tripId") Long tripId,
            @Param("queuedStatus") TravelDocumentProcessingStatus queuedStatus,
            @Param("jobId") UUID jobId,
            @Param("allowedStatuses") List<TravelDocumentProcessingStatus> allowedStatuses
    );

    @Query("""
            SELECT document FROM TravelDocument document
            WHERE document.processingStatus = PROCESSING AND document.processingStartedAt <= :cutoff
              AND (document.processingDeliveryCount >= :maxAttempts OR document.processingAttemptCount >= :maxAttempts)
            ORDER BY document.id
            """)
    List<TravelDocument> findExhaustedProcessingCandidates(@Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts, org.springframework.data.domain.Pageable page);

    @Query("""
            SELECT document FROM TravelDocument document
            WHERE (document.processingStatus = QUEUED OR
                  (document.processingStatus = PROCESSING AND document.processingStartedAt <= :cutoff))
              AND document.processingAttemptCount < :maxAttempts
              AND EXISTS (SELECT job.id FROM DocumentProcessingOutbox job
                  WHERE job.documentId = document.id AND job.processingFailedAt IS NULL
                    AND ((job.processingJobId = document.processingJobId) OR
                         (job.processingJobId IS NULL AND document.processingJobId IS NULL)))
            ORDER BY document.uploadedAt, document.id
            """)
    List<TravelDocument> findLocalProcessingCandidates(@Param("cutoff") Instant cutoff,
            @Param("maxAttempts") int maxAttempts, org.springframework.data.domain.Pageable page);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document SET document.processingStartedAt = :now
            WHERE document.id = :documentId AND document.processingStatus = PROCESSING
              AND document.processingAttemptId = :attemptId
              AND ((:jobId IS NULL AND document.processingJobId IS NULL) OR document.processingJobId = :jobId)
            """)
    int renewProcessingLease(@Param("documentId") Long documentId, @Param("jobId") UUID jobId,
            @Param("attemptId") UUID attemptId, @Param("now") Instant now);

    List<TravelDocument> findAllByTrip_IdOrderByUploadedAtDescIdDesc(Long tripId);

    Optional<TravelDocument> findByIdAndTrip_Id(
            Long documentId,
            Long tripId
    );

}
