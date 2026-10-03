package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TravelDocumentRepository extends JpaRepository<TravelDocument, Long> {

    Optional<TravelDocument> findByIdAndTrip_Id(
            Long documentId,
            Long tripId
    );

}
