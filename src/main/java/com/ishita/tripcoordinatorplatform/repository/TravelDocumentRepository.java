package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface TravelDocumentRepository extends JpaRepository<TravelDocument, Long> {

    List<TravelDocument> findAllByTrip_IdOrderByUploadedAtDescIdDesc(Long tripId);

    Optional<TravelDocument> findByIdAndTrip_Id(
            Long documentId,
            Long tripId
    );

}
