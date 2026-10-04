package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TravelDocumentExtractionRepository extends JpaRepository<TravelDocumentExtraction, Long> {

    Optional<TravelDocumentExtraction>
    findByIdAndTravelDocument_Id(
            Long extractionId,
            Long documentId
    );

}
