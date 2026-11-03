package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentProcessingOutboxRepository extends JpaRepository<DocumentProcessingOutbox, Long> {

    java.util.Optional<DocumentProcessingOutbox> findByDocumentIdAndProcessingJobId(Long documentId, java.util.UUID jobId);

    java.util.Optional<DocumentProcessingOutbox> findFirstByDocumentIdAndProcessingJobIdIsNullOrderByIdDesc(Long documentId);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            UPDATE DocumentProcessingOutbox job SET job.extractionContract = :contract
            WHERE job.documentId = :documentId AND job.processingJobId = :jobId
              AND job.extractionContract = 'AUTO_V3_4'
              AND EXISTS (SELECT document.id FROM TravelDocument document
                  WHERE document.id = :documentId AND document.processingJobId = :jobId
                    AND document.processingStatus = PROCESSING AND document.processingAttemptId = :attemptId)
            """)
    int resolveExtractionContract(@org.springframework.data.repository.query.Param("documentId") Long documentId,
            @org.springframework.data.repository.query.Param("jobId") java.util.UUID jobId,
            @org.springframework.data.repository.query.Param("attemptId") java.util.UUID attemptId,
            @org.springframework.data.repository.query.Param("contract") String contract);

    List<DocumentProcessingOutbox> findTop20ByStatusOrderByCreatedAtAscIdAsc(
            DocumentProcessingOutboxStatus status);
    boolean existsByDocumentIdAndProcessingJobIdAndProcessingFailedAtIsNotNull(Long documentId, java.util.UUID jobId);
    boolean existsByDocumentIdAndProcessingJobIdIsNullAndProcessingFailedAtIsNotNull(Long documentId);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            UPDATE DocumentProcessingOutbox entry
            SET entry.processingFailedAt = COALESCE(entry.processingFailedAt, :failedAt)
            WHERE entry.documentId = :documentId AND entry.processingJobId = :jobId
            """)
    int markGenerationFailed(@org.springframework.data.repository.query.Param("documentId") Long documentId,
            @org.springframework.data.repository.query.Param("jobId") java.util.UUID jobId,
            @org.springframework.data.repository.query.Param("failedAt") java.time.Instant failedAt);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            UPDATE DocumentProcessingOutbox entry
            SET entry.processingFailedAt = COALESCE(entry.processingFailedAt, :failedAt)
            WHERE entry.id = (SELECT MAX(previous.id) FROM DocumentProcessingOutbox previous
                WHERE previous.documentId = :documentId AND previous.processingJobId IS NULL AND previous.status = PUBLISHED)
            """)
    int markLatestLegacyJobFailed(@org.springframework.data.repository.query.Param("documentId") Long documentId,
            @org.springframework.data.repository.query.Param("failedAt") java.time.Instant failedAt);

}
