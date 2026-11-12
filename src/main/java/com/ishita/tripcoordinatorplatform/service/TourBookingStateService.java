package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.Change;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Effective read view over immutable extractions, accepted reviews and durable confirmation receipts. */
@Service
public class TourBookingStateService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final TourCorrectionReviewRepository reviews;
    private final BookingConfirmationReceiptRepository receipts;
    private final ReservationRepository reservations;
    private final TourConfirmationPolicy policy;
    private final JsonMapper mapper;
    public TourBookingStateService(TravelDocumentRepository documents,TravelDocumentExtractionRepository extractions,
            TourCorrectionReviewRepository reviews,BookingConfirmationReceiptRepository receipts,ReservationRepository reservations,TourConfirmationPolicy policy,JsonMapper mapper){
        this.documents=documents;this.extractions=extractions;this.reviews=reviews;this.receipts=receipts;this.reservations=reservations;this.policy=policy;this.mapper=mapper;
    }
    public record ReviewVersion(Long reviewId,long version) {}
    public enum Status { CONFIRMED, CONFIRMED_RESERVATION_DELETED, PENDING_ACTIVE, PENDING_REVIEW, HISTORICAL }
    public record Booking(int bookingIndex,Status status,ReadingFact<ReadingBookingLifecycle.State> originalLifecycle,
            LifecycleClarification acceptedLifecycle,BookingEligibility.Booking eligibility,
            com.ishita.tripcoordinatorplatform.ai.document.AttractionTourBookingExtraction effectiveBooking,
            java.time.LocalDateTime requiredArrivalLocal,String schedulingBlockCode,Long receiptId,Long historicalReservationId,ReviewVersion acceptedReview) {}
    public record Snapshot(Long documentId,Long extractionId,String extractionSha256,String acceptedStateToken,
            TravelDocumentProcessingStatus documentStatus,List<ReviewVersion> acceptedReviews,List<Booking> bookings) {}
    record Composition(TravelDocumentExtraction extraction,ObjectNode facts,List<Change> changes,
                       BookingEligibility.Selection eligibility,List<TourCorrectionReview> accepted,String token) {}
    boolean currentReading(String source){var d=new TourExtractionCodec(mapper).decode(source);return d.reading()!=null && TourReading.isCurrentContract(d.reading().provenance().promptVersion());}
    void requireCurrent(String source){
        policy.requireLegacyExtraction(source);
        if(!currentReading(source))throw TourCorrectionException.validation("BOOKING_LIFECYCLE_REVIEW_REQUIRED","Partial confirmation requires the exact V3.4 lifecycle contract; historical source remains readable.");
    }
    List<TourCorrectionReview> accepted(TravelDocument document,TravelDocumentExtraction extraction){
        return reviews.findAllByDocumentIdOrderByCreatedAtDescIdDesc(document.getId()).stream()
                .map(r->org.hibernate.Hibernate.unproxy(r,TourCorrectionReview.class))
                .filter(r->r.acceptedAt!=null && r.extractionId.equals(extraction.getId()))
                .sorted(Comparator.comparing((TourCorrectionReview r)->r.acceptedAt).thenComparing(r->r.id)).toList();
    }
    String token(List<TourCorrectionReview> accepted){return TourCorrectionService.hash(accepted.stream().sorted(Comparator.comparing(r->r.id)).map(r->r.id+":"+r.version).collect(java.util.stream.Collectors.joining(",")));}
    List<Change> changes(TourCorrectionReview review){return Arrays.asList(mapper.readValue(review.changesJson,Change[].class));}
    Composition compose(TravelDocument document,TravelDocumentExtraction extraction,List<Change> extra){
        requireCurrent(extraction.getExtractedData());
        var accepted=accepted(document,extraction);var merged=new LinkedHashMap<String,Change>();
        for(var review:accepted){
            if(!Objects.equals(review.actorId,document.getUploadedByUserId()) || !Objects.equals(review.acceptedBy,review.actorId)
                    || !review.extractionSha256.equals(TourCorrectionService.hash(extraction.getExtractedData())))throw TourCorrectionException.stale("Accepted correction source/ownership mismatch.");
            for(var c:changes(review))merged.put(c.bookingIndex()+":"+c.field(),c);
        }
        for(var c:extra)merged.put(c.bookingIndex()+":"+c.field(),c);
        var combined=List.copyOf(merged.values());var facts=policy.proposedCombined(extraction.getExtractedData(),combined);
        return new Composition(extraction,facts,combined,policy.eligibility(facts,TourConfirmationPolicy.correctedFields(combined)),accepted,token(accepted));
    }
    void requireToken(Composition state,String expected){if(!Objects.equals(state.token(),expected))throw TourCorrectionException.stale("Accepted booking information changed; reload its current state.");}
    void requireMutable(TravelDocumentExtraction extraction,List<Change> changes){
        var confirmed=new HashSet<Integer>();
        for(var receipt:receipts.findAllByExtractionIdOrderByOriginalBookingIndexAsc(extraction.getId()))confirmed.add(receipt.getOriginalBookingIndex());
        // Existing confirmed records predating receipts remain protected without speculative backfill.
        for(var reservation:reservations.findByTrip_Id(extraction.getTravelDocument().getTrip().getId()))
            if(reservation.getConfirmedExtraction()!=null && extraction.getId().equals(reservation.getConfirmedExtraction().getId()))confirmed.add(reservation.getConfirmedBookingIndex());
        if(changes.stream().anyMatch(c->confirmed.contains(c.bookingIndex())))throw TourCorrectionException.stale("Already confirmed source bookings require a separate amendment workflow.");
    }
    TourCorrectionReview lastReview(Composition state,int index){
        TourCorrectionReview latest=null;
        for(var review:state.accepted())if(changes(review).stream().anyMatch(c->c.bookingIndex()==index))latest=review;
        return latest;
    }
    void requireNotConfirmed(TravelDocumentExtraction extraction,Set<Integer> selected){
        var synthetic=selected.stream().map(i->new Change(i,"lifecycle",mapper.nullNode(),mapper.nullNode())).toList();requireMutable(extraction,synthetic);
    }
    boolean complete(Composition state){
        var done=new HashSet<Integer>();for(var r:receipts.findAllByExtractionIdOrderByOriginalBookingIndexAsc(state.extraction().getId()))done.add(r.getOriginalBookingIndex());
        return !done.isEmpty() && state.eligibility().bookings().stream().allMatch(b->done.contains(b.bookingIndex()) || b.disposition()==BookingEligibility.Disposition.HISTORICAL);
    }
    com.ishita.tripcoordinatorplatform.response.TourBookingPreview preview(TravelDocument doc,Composition state){
        var snapshot=snapshot(doc,state);var invalidated=policy.invalidateReadingDerived(state.facts(),TourConfirmationPolicy.correctedFields(state.changes()));var rows=snapshot.bookings().stream().map(b->new com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking(b.bookingIndex(),
                b.effectiveBooking(),b.requiredArrivalLocal(),state.facts().path("bookings").get(b.bookingIndex()).path("endingLocationChoice").asText("UNKNOWN"),invalidated.getOrDefault(b.bookingIndex(),Set.of()),
                b.status()==Status.PENDING_ACTIVE && b.schedulingBlockCode()==null,b.eligibility(),b.acceptedLifecycle())).toList();
        return new com.ishita.tripcoordinatorplatform.response.TourBookingPreview(3,new TourExtractionCodec(mapper).decode(state.extraction().getExtractedData()).reading().provenance().promptVersion(),state.extraction().getId(),rows,
                rows.stream().anyMatch(com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking::confirmationEligible),null,snapshot.bookings().stream().filter(b->b.status()==Status.PENDING_ACTIVE).allMatch(b->b.schedulingBlockCode()==null));
    }
    @Transactional(readOnly=true)
    public Snapshot snapshot(Long tripId,Long documentId,Long actor){
        var doc=documents.findByIdAndTrip_Id(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Objects.equals(doc.getUploadedByUserId(),actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId).orElseThrow(()->TourCorrectionException.stale("Source extraction unavailable."));
        return snapshot(doc,compose(doc,extraction,List.of()));
    }
    Snapshot snapshot(TravelDocument doc,Composition state){
        var v3=new TourExtractionCodec(mapper).decode(state.extraction().getExtractedData()).reading();
        var history=new HashMap<Integer,BookingConfirmationReceipt>();for(var r:receipts.findAllByExtractionIdOrderByOriginalBookingIndexAsc(state.extraction().getId()))history.put(r.getOriginalBookingIndex(),r);
        var rows=new ArrayList<Booking>();var corrected=TourConfirmationPolicy.correctedFields(state.changes());
        for(var original:v3.bookings()){
            int i=original.bookingIndex();var eligibility=state.eligibility().bookings().get(i);var receipt=history.get(i);var last=lastReview(state,i);
            LifecycleClarification clarification=null;var node=state.facts().path("bookings").get(i).path("lifecycle");
            if(corrected.getOrDefault(i,Set.of()).contains("lifecycle"))clarification=mapper.treeToValue(node,LifecycleClarification.class);
            Status status=eligibility.disposition()==BookingEligibility.Disposition.HISTORICAL?Status.HISTORICAL:
                    eligibility.disposition()==BookingEligibility.Disposition.ACTIVE_ELIGIBLE?Status.PENDING_ACTIVE:Status.PENDING_REVIEW;
            if(receipt!=null)status=reservations.existsById(receipt.getReservationId())?Status.CONFIRMED:Status.CONFIRMED_RESERVATION_DELETED;
            var proposal=state.facts().path("bookings").get(i).deepCopy();((ObjectNode)proposal).remove("lifecycle");((ObjectNode)proposal).remove("requiredArrivalLocal");((ObjectNode)proposal).remove("endingLocationChoice");
            java.time.LocalDateTime arrival=null;String block=null;
            if(receipt==null && eligibility.disposition()==BookingEligibility.Disposition.ACTIVE_ELIGIBLE)try{
                var effective=policy.validateSelected(state.facts(),corrected,Set.of(i));var at=effective.arrivals().get(i);
                if(at!=null)arrival=java.time.LocalDateTime.ofInstant(at,java.time.ZoneId.of(effective.proposal().bookings().get(i).timezone()));
            }catch(TourCorrectionException invalid){block=invalid.code();}
            rows.add(new Booking(i,status,original.lifecycle(),clarification,eligibility,mapper.treeToValue(proposal,AttractionTourBookingExtraction.class),arrival,block,
                    receipt==null?null:receipt.getId(),receipt==null?null:receipt.getReservationId(),last==null?null:new ReviewVersion(last.id,last.version)));
        }
        return new Snapshot(doc.getId(),state.extraction().getId(),TourCorrectionService.hash(state.extraction().getExtractedData()),state.token(),doc.getProcessingStatus(),
                state.accepted().stream().map(r->new ReviewVersion(r.id,r.version)).toList(),List.copyOf(rows));
    }
}
