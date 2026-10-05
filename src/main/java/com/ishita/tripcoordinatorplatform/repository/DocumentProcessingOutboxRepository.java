package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentProcessingOutboxRepository extends JpaRepository<DocumentProcessingOutbox, Long> {

    List<DocumentProcessingOutbox> findTop20ByStatusOrderByCreatedAtAscIdAsc(
            DocumentProcessingOutboxStatus status);
}
