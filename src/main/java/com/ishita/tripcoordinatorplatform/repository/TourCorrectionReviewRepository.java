package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TourCorrectionReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TourCorrectionReviewRepository extends JpaRepository<TourCorrectionReview,Long> {
    java.util.List<TourCorrectionReview> findAllByDocumentIdOrderByCreatedAtDescIdDesc(Long documentId);
    Optional<TourCorrectionReview> findByDocumentIdAndActorIdAndRequestId(Long documentId,Long actorId,UUID requestId);
}
