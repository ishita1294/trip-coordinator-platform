package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface TravelDocumentExtractionRepository extends JpaRepository<TravelDocumentExtraction, Long> {

    List<TravelDocumentExtraction> findAllByTravelDocument_IdOrderByCreatedAtDescIdDesc(Long documentId);

    Optional<TravelDocumentExtraction> findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(Long documentId);

    Optional<TravelDocumentExtraction>
    findByIdAndTravelDocument_Id(
            Long extractionId,
            Long documentId
    );

}
