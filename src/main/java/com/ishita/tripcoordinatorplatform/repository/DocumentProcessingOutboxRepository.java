package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentProcessingOutboxRepository extends JpaRepository<DocumentProcessingOutbox, Long> {
}
