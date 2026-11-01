package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec;
import com.ishita.tripcoordinatorplatform.model.BookingConfirmationReceipt;
import com.ishita.tripcoordinatorplatform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Objects;

/** Internal transaction participant only. Does not select bookings or initiate confirmation. */
@Service
public class BookingConfirmationReceiptService {
    private final BookingConfirmationReceiptRepository receipts;
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final ReservationRepository reservations;
    private final TourCorrectionReviewRepository reviews;
    private final JsonMapper mapper;
    public BookingConfirmationReceiptService(BookingConfirmationReceiptRepository receipts,TravelDocumentRepository documents,
            TravelDocumentExtractionRepository extractions,ReservationRepository reservations,TourCorrectionReviewRepository reviews,JsonMapper mapper){
        this.receipts=receipts;this.documents=documents;this.extractions=extractions;this.reservations=reservations;this.reviews=reviews;this.mapper=mapper;
    }
    public record Record(Long tripId,Long documentId,Long actorId,Long extractionId,int originalBookingIndex,
                         Long reservationId,String expectedExtractionSha256,Long acceptedReviewId,Long acceptedReviewVersion) {}
    @Transactional(propagation=Propagation.MANDATORY)
    public BookingConfirmationReceipt record(Record command){return record(command,null);}
    /** Explicit inactive native entry; ordinary legacy recording never dispatches here. */
    @Transactional(propagation=Propagation.MANDATORY)
    public BookingConfirmationReceipt recordSourceBookingV1(Record command,com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput original){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Native receipt must participate in the confirmation transaction");
        if(original==null)throw TourCorrectionException.invalid("Native receipt requires the trusted original source.");
        return record(command,original);
    }
    private BookingConfirmationReceipt record(Record command,com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput nativeSource){
        if(command==null || command.actorId()==null || command.extractionId()==null || command.reservationId()==null || command.originalBookingIndex()<0)
            throw TourCorrectionException.invalid("Identify the confirmed source booking, reservation and actor.");
        var document=documents.findForConfirmation(command.documentId(),command.tripId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Objects.equals(document.getUploadedByUserId(),command.actorId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(document.getId()).orElseThrow(()->TourCorrectionException.stale("Source extraction is unavailable."));
        String hash=TourCorrectionService.hash(extraction.getExtractedData());
        if(!extraction.getId().equals(command.extractionId()) || !hash.equals(command.expectedExtractionSha256()))
            throw TourCorrectionException.stale("Confirm the exact immutable source version.");
        int schemaVersion;String prompt;String nativeSourceHash=null;
        if(nativeSource==null) {
            var decoded=new TourExtractionCodec(mapper).decode(extraction.getExtractedData());schemaVersion=decoded.schemaVersion();
            prompt=schemaVersion==1?null:mapper.readTree(extraction.getExtractedData()).path("provenance").path("promptVersion").asString();
        } else {
            var nativeRead=new com.ishita.tripcoordinatorplatform.ai.document.SourceBookingReaderV1().read(extraction.getExtractedData(),mapper,nativeSource);
            schemaVersion=nativeRead.facts().schemaVersion();prompt=nativeRead.facts().contract();nativeSourceHash=nativeRead.sourceSha256();
        }
        var tree=mapper.readTree(extraction.getExtractedData());
        if(!tree.path("bookings").isArray() || command.originalBookingIndex()>=tree.path("bookings").size())
            throw TourCorrectionException.invalid("Invalid original source booking index.");
        if(receipts.findByExtractionIdAndOriginalBookingIndex(extraction.getId(),command.originalBookingIndex()).isPresent())
            throw TourCorrectionException.stale("This source booking already has a confirmation receipt, even if its reservation was deleted.");
        var reservation=reservations.findById(command.reservationId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Objects.equals(reservation.getTrip().getId(),command.tripId()) || reservation.getConfirmedExtraction()==null
                || !extraction.getId().equals(reservation.getConfirmedExtraction().getId())
                || !Objects.equals(reservation.getConfirmedBookingIndex(),command.originalBookingIndex())
                || !Objects.equals(reservation.getCorrectionReviewId(),command.acceptedReviewId()))
            throw TourCorrectionException.stale("Reservation does not match the source booking and accepted review.");
        if((command.acceptedReviewId()==null)!=(command.acceptedReviewVersion()==null))throw TourCorrectionException.invalid("Identify both accepted review and version.");
        if(command.acceptedReviewId()!=null){
            // Reservation loading may already have installed a lazy review proxy. Public audit
            // fields belong to its initialized target, not to the proxy instance.
            var review=org.hibernate.Hibernate.unproxy(reviews.findById(command.acceptedReviewId())
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)),com.ishita.tripcoordinatorplatform.model.TourCorrectionReview.class);
            if(!Objects.equals(review.documentId,document.getId()) || !Objects.equals(review.extractionId,extraction.getId())
                    || !Objects.equals(review.actorId,command.actorId()) || !Objects.equals(review.acceptedBy,command.actorId())
                    || review.acceptedAt==null || review.version!=command.acceptedReviewVersion() || !hash.equals(review.extractionSha256)
                    || nativeSourceHash!=null && !nativeSourceHash.equals(review.documentSha256))
                throw TourCorrectionException.stale("Receipt requires the exact accepted review version and source.");
        }
        return receipts.saveAndFlush(new BookingConfirmationReceipt(extraction.getId(),command.originalBookingIndex(),command.actorId(),Instant.now(),
                reservation.getId(),hash,schemaVersion,prompt,command.acceptedReviewId(),command.acceptedReviewVersion()));
    }
}
