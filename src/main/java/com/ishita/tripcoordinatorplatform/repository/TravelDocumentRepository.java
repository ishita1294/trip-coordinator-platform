package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface TravelDocumentRepository extends JpaRepository<TravelDocument, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TravelDocument document
            SET document.processingStatus = :queuedStatus
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
