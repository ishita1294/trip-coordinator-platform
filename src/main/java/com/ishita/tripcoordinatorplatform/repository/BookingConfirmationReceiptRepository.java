package com.ishita.tripcoordinatorplatform.repository;

import com.ishita.tripcoordinatorplatform.model.BookingConfirmationReceipt;
import org.springframework.data.repository.Repository;
import java.util.*;

/** Deliberately exposes no ordinary update/delete operations. */
public interface BookingConfirmationReceiptRepository extends Repository<BookingConfirmationReceipt,Long> {
    @org.springframework.data.jpa.repository.Query("select count(r)>0 from BookingConfirmationReceipt r, TravelDocumentExtraction e where r.extractionId=e.id and e.travelDocument.id=:documentId")
    boolean existsForDocument(@org.springframework.data.repository.query.Param("documentId") Long documentId);
    BookingConfirmationReceipt saveAndFlush(BookingConfirmationReceipt receipt);
    List<BookingConfirmationReceipt> findAllByReservationId(Long reservationId);
    Optional<BookingConfirmationReceipt> findByExtractionIdAndOriginalBookingIndex(Long extractionId,int originalBookingIndex);
    List<BookingConfirmationReceipt> findAllByExtractionIdOrderByOriginalBookingIndexAsc(Long extractionId);
}
