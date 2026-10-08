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

    Optional<TravelDocument> findByStorageKey(String storageKey);

    @Query("SELECT document.documentType FROM TravelDocument document WHERE document.id = :documentId AND document.trip.id = :tripId")
    Optional<TravelDocumentType> findDocumentTypeForDispatch(@Param("documentId") Long documentId,
                                                           @Param("tripId") Long tripId);

    boolean existsByIdAndTrip_Id(Long documentId, Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT document FROM TravelDocument document WHERE document.id = :documentId AND document.trip.id = :tripId")
    Optional<TravelDocument> findForConfirmation(@Param("documentId") Long documentId,
                                                @Param("tripId") Long tripId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStatus = :failureStatus,
                document.processingStartedAt = NULL,
                document.processingAttemptId = NULL
            WHERE document.id = :documentId
              AND document.processingStatus = PROCESSING
              AND document.processingAttemptId = :attemptId
            """)
    int failProcessingAttempt(@Param("documentId") Long documentId,
                              @Param("attemptId") UUID attemptId,
                              @Param("failureStatus") TravelDocumentProcessingStatus failureStatus);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStatus = PROCESSING,
                document.processingStartedAt = :now,
                document.processingAttemptId = :attemptId
            WHERE document.id = :documentId
              AND document.processingStatus = QUEUED
            """)
    int claimQueuedForProcessing(@Param("documentId") Long documentId, @Param("now") Instant now,
                                 @Param("attemptId") UUID attemptId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStartedAt = :now,
                document.processingAttemptId = :attemptId
            WHERE document.id = :documentId
              AND document.processingStatus = PROCESSING
              AND document.processingStartedAt <= :staleBefore
            """)
    int reclaimStaleProcessing(@Param("documentId") Long documentId,
                               @Param("now") Instant now,
                               @Param("staleBefore") Instant staleBefore,
                               @Param("attemptId") UUID attemptId);

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
            @Param("allowedStatuses") List<TravelDocumentProcessingStatus> allowedStatuses
    );

    List<TravelDocument> findAllByTrip_IdOrderByUploadedAtDescIdDesc(Long tripId);

    Optional<TravelDocument> findByIdAndTrip_Id(
            Long documentId,
            Long tripId
    );

}
