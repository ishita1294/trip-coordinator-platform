package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.auth.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.*;
import com.ishita.tripcoordinatorplatform.response.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

/** Explicit inactive native boundary: not a bean/controller and never selected by legacy/default routing. */
public class SourceBookingConfirmationService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final ReservationRepository reservationRepository;
    private final BookingConfirmationReceiptRepository receipts;
    private final TourBookingStateService state;
    private final SourceBookingAcceptedReviewService review;
    private final BookingConfirmationReceiptService recorder;
    private final ReservationService reservations;
    private final ItineraryService itineraries;
    private final ItineraryItemService items;
    private final TripAccessService trips;
    private final DocumentAccessService access;
    private final JsonMapper mapper;
    public SourceBookingConfirmationService(TravelDocumentRepository documents,TravelDocumentExtractionRepository extractions,
            ReservationRepository reservationRepository,BookingConfirmationReceiptRepository receipts,TourBookingStateService state,
            SourceBookingAcceptedReviewService review,BookingConfirmationReceiptService recorder,ReservationService reservations,
            ItineraryService itineraries,ItineraryItemService items,TripAccessService trips,DocumentAccessService access,JsonMapper mapper) {
        this.documents=documents;this.extractions=extractions;this.reservationRepository=reservationRepository;this.receipts=receipts;
        this.state=state;this.review=review;this.recorder=recorder;this.reservations=reservations;this.itineraries=itineraries;
        this.items=items;this.trips=trips;this.access=access;this.mapper=mapper;
    }
    public record Command(boolean explicitlyApproved,String extractionSha256,ConfirmAttractionTourDocumentRequest review) {}
    private record Prepared(int index,Reservation reservation,CreateItineraryItemRequest item,TourCorrectionReview acceptedReview) {}
    /** Trusted storage caller supplies immutable original bytes; these are never an HTTP request field. */
    @Transactional
    public ConfirmDocumentResponse confirm(Long tripId,Long documentId,Long actor,Command command,DocumentAiInput original) {
        if(command==null || !command.explicitlyApproved() || command.review()==null)
            throw TourCorrectionException.invalid("Explicit approval of the exact selected native bookings is required.");
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Native confirmation requires one active transaction");
        if(original==null || original.content()==null || original.content().length==0 || original.content().length>20*1024*1024)
            throw TourCorrectionException.invalid("Trusted original source is unavailable or oversized.");
        original=new DocumentAiInput(original.originalFileName(),original.contentType(),original.content().clone());
        trips.requireEditor(tripId,actor);access.require(tripId,documentId,true);
        var doc=documents.findForConfirmation(documentId,tripId).orElseThrow(SourceBookingConfirmationService::missing);
        if(actor==null || !Objects.equals(doc.getUploadedByUserId(),actor))throw missing();
        if(doc.getDocumentType()!=TravelDocumentType.TOUR_BOOKING && doc.getDocumentType()!=TravelDocumentType.ATTRACTION_TICKET)
            throw TourCorrectionException.invalid("Only attraction/tour documents support native confirmation.");
        if(doc.getProcessingStatus()!=TravelDocumentProcessingStatus.REVIEW_REQUIRED)
            throw TourCorrectionException.stale("Document is not awaiting native review.");
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId).orElseThrow(SourceBookingConfirmationService::missing);
        var request=command.review();
        if(!Objects.equals(request.extractionId(),extraction.getId()) || !Objects.equals(command.extractionSha256(),TourCorrectionService.hash(extraction.getExtractedData()))
                || extraction.getTravelDocument()==null || !Objects.equals(extraction.getTravelDocument().getId(),doc.getId()))
            throw TourCorrectionException.stale("Confirm the exact immutable extraction/version.");
        if(request.correctionAcceptance()!=null || nonempty(request.arrivalReviews()) || nonempty(request.endingReviews()) || nonempty(request.endpointClarifications()))
            throw TourCorrectionException.invalid("Accept native source corrections separately; legacy clarification fields cannot override native facts.");
        var current=review.compose(doc,extraction,original,List.of()); // Native reader verifies distinct version and original quotations.
        if(!Objects.equals(request.acceptedStateToken(),current.acceptedStateToken()))throw TourCorrectionException.stale("Accepted booking state changed; reload review.");
        var selected=request.selectedBookingIndexes();
        if(selected==null || selected.isEmpty() || selected.stream().anyMatch(i->i==null || i<0 || i>=current.bookings().size())
                || new HashSet<>(selected).size()!=selected.size())
            throw TourCorrectionException.validation("BOOKING_SELECTION_REQUIRED","Select distinct original booking indices explicitly.");
        var indexes=selected.stream().sorted().toList();
        state.requireNotConfirmed(extraction,new HashSet<>(indexes)); // Receipts survive deleted reservations; no recreation.
        var accepted=state.accepted(doc,extraction);var prepared=new ArrayList<Prepared>();var references=new HashSet<String>();
        for(int index:indexes) {
            var row=current.bookings().get(index);
            if(!row.eligibility().reviewReady())throw TourCorrectionException.validation("BOOKING_NOT_ELIGIBLE","Selected booking has unresolved or unrepresentable native information.");
            var value=row.currentValues();String zone=text(value,"timezone");LocalDateTime start=at(value,"startDate","startTime",true);LocalDateTime end=at(value,"endDate","endTime",false);
            var startInstant=instant(start,zone);if(end!=null && instant(end,zone).isBefore(startInstant))throw blocked();
            var arrival=value.get("requiredArrivalLocal").isNull()?null:LocalDateTime.parse(value.get("requiredArrivalLocal").asText());
            if(arrival!=null && instant(arrival,zone).isAfter(startInstant))throw blocked();
            String reference=text(value,"bookingReference");
            if(reference!=null && !reference.isBlank() && (!references.add(reference)||reservationRepository.existsByTrip_IdAndConfirmationNumber(tripId,reference)))
                throw TourCorrectionException.invalid("Reservation with this confirmation number already exists.");
            var reservation=new Reservation();reservation.setType(ReservationType.ACTIVITY);reservation.setName(text(value,"name"));
            reservation.setProvider(text(value,"provider"));reservation.setStartDateTime(start);reservation.setEndDateTime(end);
            // Reservation stores documentary name/address only; richer source location remains in native details.
            reservation.setStructuredLocation(location(value.get("meetingPoint")));
            String ending=text(value,"endingLocationChoice");
            if(!Set.of("UNKNOWN","SAME_AS_MEETING_POINT","LOCATION").contains(ending))throw blocked();
            reservation.reviewEndingLocation(new ActivityEndingLocationRequest(ending,ending.equals("LOCATION")?location(value.get("endingPoint")):null,null,0L));
            reservation.recordConfirmedSchedule(zone,extraction,index);
            if(arrival!=null)reservation.recordReviewedArrival(instant(arrival,zone));
            TourCorrectionReview last=null;
            for(var r:accepted)if(state.changes(r).stream().anyMatch(c->Objects.equals(c.bookingIndex(),index)))last=r;
            if(last!=null)reservation.recordCorrectionReview(last);
            if(reference!=null&&!reference.isBlank()){reservation.setConfirmationNumber(reference);reservation.setConfirmationNumberType(ReservationConfirmationType.BOOKING_ID);}
            var item=new CreateItineraryItemRequest();item.setTitle(reservation.getName());item.setStartDateTime(start);item.setEndDateTime(end);
            item.setFlexibility(end==null?ItineraryItemFlexibility.TIME_CONSTRAINED:ItineraryItemFlexibility.FIXED);item.setParticipantIds(request.participantIds());
            prepared.add(new Prepared(index,reservation,item,last));
        }
        // Existing trip lock/itinerary repair and participant/conflict services run in the same transaction.
        itineraries.ensureItinerary(tripId);
        // Membership changes use the same trip lock; recheck after it is acquired, before any reservation write.
        trips.requireEditor(tripId,actor);access.require(tripId,documentId,true);
        var ids=new ArrayList<Long>();
        for(var entry:prepared) {
            var saved=reservations.createReservation(tripId,entry.reservation());entry.item().setReservationId(saved.getId());items.createItineraryItem(tripId,entry.item());
            var last=entry.acceptedReview();
            recorder.recordSourceBookingV1(new BookingConfirmationReceiptService.Record(tripId,documentId,actor,extraction.getId(),entry.index(),saved.getId(),
                    current.extractionSha256(),last==null?null:last.id,last==null?null:last.version),original);
            ids.add(saved.getId());
        }
        var done=new HashSet<Integer>();for(var receipt:receipts.findAllByExtractionIdOrderByOriginalBookingIndexAsc(extraction.getId()))done.add(receipt.getOriginalBookingIndex());
        boolean complete=current.bookings().stream().allMatch(b->done.contains(b.index()) || b.eligibility().lifecycleDisposition()==SemanticBookingEligibility.Disposition.HISTORICAL);
        doc.setProcessingStatus(complete?TravelDocumentProcessingStatus.PROCESSED:TravelDocumentProcessingStatus.REVIEW_REQUIRED);documents.save(doc);
        return new ConfirmDocumentResponse(List.copyOf(ids),indexes,doc.getProcessingStatus());
    }
    /** Native versioned detail view for future BookingInformation/Ask AI dispatch; legacy DTO/read remains unchanged. */
    public record Information(Long reservationId,BookingInformation.Presence presence,BookingInformation.CurrentBooking current,
            BookingInformation.SourceStatus sourceStatus,NativeDetails nativeDetails) {}
    public record NativeDetails(String contract,int schemaVersion,Long documentId,Long extractionId,int bookingIndex,String extractionSha256,
            String sourceSha256,SourceBookingExtractionV1.Booking originalBooking,SourceBookingAcceptedReviewService.BookingOverlay confirmedValues,
            List<SourceBookingReviewV1.ClauseView> clauses,List<SourceBookingReviewV1.ClauseLink> applicability,List<SourceBookingExtractionV1.Source> sources,
            List<SourceBookingAcceptedReviewService.Acceptance> acceptedHistory,List<BookingInformation.AcceptedReview> correctionEvidence,
            BookingInformation.Confirmation confirmation,String coverageWarning) {}
    @Transactional(readOnly=true)
    public Information information(Long tripId,Long reservationId,Long actor,DocumentAiInput original) {
        trips.requireMembership(tripId,actor);
        var reservation=reservationRepository.findByIdAndTrip_Id(reservationId,tripId).orElse(null);
        var found=receipts.findAllByReservationId(reservationId);if(found.size()!=1)throw missing();var receipt=found.getFirst();
        var extraction=extractions.findById(receipt.getExtractionId()).orElseThrow(SourceBookingConfirmationService::missing);
        var doc=extraction.getTravelDocument();if(doc==null||doc.getTrip()==null||!Objects.equals(doc.getTrip().getId(),tripId))throw missing();
        if(!Objects.equals(doc.getUploadedByUserId(),actor)) {
            if(reservation==null)throw missing();return new Information(reservationId,BookingInformation.Presence.SAVED,current(reservation),BookingInformation.SourceStatus.RESTRICTED,null);
        }
        access.require(tripId,doc.getId(),false);
        int index=receipt.getOriginalBookingIndex();
        if(!Objects.equals(receipt.getReservationId(),reservationId) || !Objects.equals(receipt.getExtractionSha256(),TourCorrectionService.hash(extraction.getExtractedData()))
                || receipt.getSchemaVersion()!=SourceBookingExtractionV1.SCHEMA_VERSION || !Objects.equals(receipt.getPromptVersion(),SourceBookingExtractionV1.CONTRACT)
                || !Objects.equals(receipt.getConfirmedBy(),doc.getUploadedByUserId())
                || reservation!=null&&(reservation.getType()!=ReservationType.ACTIVITY||reservation.getConfirmedExtraction()==null
                ||!Objects.equals(reservation.getConfirmedExtraction().getId(),extraction.getId())||!Objects.equals(reservation.getConfirmedBookingIndex(),index)
                ||!Objects.equals(reservation.getCorrectionReviewId(),receipt.getAcceptedReviewId())))throw TourCorrectionException.stale("Native confirmation provenance is inconsistent.");
        var atConfirmation=review.composeAt(doc,extraction,original,receipt.getConfirmedAt());
        if(index<0||index>=atConfirmation.bookings().size())throw TourCorrectionException.stale("Native source booking index changed.");
        var history=atConfirmation.history().stream().filter(h->h.bookingIndex()==index).toList();
        var last=history.isEmpty()?null:history.getLast();
        if(last==null?receipt.getAcceptedReviewId()!=null||receipt.getAcceptedReviewVersion()!=null:
                !Objects.equals(last.reviewId(),receipt.getAcceptedReviewId())||!Objects.equals(last.version(),receipt.getAcceptedReviewVersion()))
            throw TourCorrectionException.stale("Receipt and accepted correction version disagree.");
        var correctionEvidence=new ArrayList<BookingInformation.AcceptedReview>();
        for(var accepted:state.accepted(doc,extraction))if(!accepted.acceptedAt.isAfter(receipt.getConfirmedAt())) {
            var changes=state.changes(accepted).stream().filter(c->Objects.equals(c.bookingIndex(),index)).toList();
            if(changes.isEmpty())continue;
            var assessed=TourCorrectionService.normalize(mapper.readValue(accepted.assessmentJson,TourSourceAssessment.class),state.changes(accepted));
            correctionEvidence.add(new BookingInformation.AcceptedReview(accepted.id,accepted.version,accepted.acceptedBy,accepted.acceptedAt,
                    changes,assessed.findings().stream().filter(f->f.bookingIndex()==index).toList()));
        }
        var row=atConfirmation.original().bookings().get(index);var linked=row.clauses();
        var clauses=linked.stream().map(l->atConfirmation.original().clauses().get(l.clauseIndex())).toList();
        var confirmation=new BookingInformation.Confirmation(receipt.getId(),receipt.getConfirmedBy(),receipt.getConfirmedAt(),receipt.getReservationId(),receipt.getAcceptedReviewId(),receipt.getAcceptedReviewVersion());
        var details=new NativeDetails(SourceBookingExtractionV1.CONTRACT,SourceBookingExtractionV1.SCHEMA_VERSION,doc.getId(),extraction.getId(),index,
                atConfirmation.extractionSha256(),atConfirmation.original().sourceSha256(),row.original(),atConfirmation.bookings().get(index),clauses,linked,
                atConfirmation.original().sources(),history,List.copyOf(correctionEvidence),confirmation,atConfirmation.original().coverageWarning());
        return new Information(reservationId,reservation==null?BookingInformation.Presence.RESERVATION_DELETED:BookingInformation.Presence.SAVED,
                current(reservation),BookingInformation.SourceStatus.AVAILABLE,details);
    }
    private static boolean nonempty(List<?> list){return list!=null&&!list.isEmpty();}
    private static String text(Map<String,JsonNode> v,String key){var n=v.get(key);return n==null||n.isNull()?null:n.asText();}
    private static LocalDateTime at(Map<String,JsonNode> v,String date,String time,boolean required) {
        String d=text(v,date),t=text(v,time);if(d==null&&t==null&&!required)return null;
        if(d==null||t==null)throw blocked();return LocalDateTime.of(LocalDate.parse(d),LocalTime.parse(t));
    }
    private static Instant instant(LocalDateTime at,String zone) {
        if(zone==null)throw blocked();var offsets=ZoneId.of(zone).getRules().getValidOffsets(at);if(offsets.size()!=1)throw blocked();return at.toInstant(offsets.getFirst());
    }
    private Location location(JsonNode node) {
        if(node==null||node.isNull())return null;var l=mapper.treeToValue(node,SemanticTourExtraction.DocumentLocation.class);
        var result=new Location();result.setName(l.name());result.setAddress(l.address());return result;
    }
    private static BookingInformation.CurrentBooking current(Reservation r){return r==null?null:new BookingInformation.CurrentBooking(r.getType(),r.getName(),r.getProvider(),r.getConfirmationNumber(),r.getConfirmationNumberType(),
            r.getStatus(),r.getStartDateTime(),r.getEndDateTime(),r.getScheduleTimeZone(),r.getRequiredArrivalAt(),r.getLocation(),r.getStructuredLocation(),r.getEndingLocation(),r.getEndingPlaceId(),r.getEndingLocationSource(),r.getEndingLocationVersion(),r.getEndpointClarification(),r.getEndpointUpdate());}
    private static TourCorrectionException blocked(){return TourCorrectionException.validation("NATIVE_RESERVATION_NOT_REPRESENTABLE","Native schedule cannot be faithfully represented; no fallback was applied.");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND);}
}
