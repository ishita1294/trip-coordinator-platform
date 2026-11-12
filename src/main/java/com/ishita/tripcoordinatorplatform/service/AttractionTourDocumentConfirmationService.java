package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourExtractionResult;
import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourExtractionValidator;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.ReservationRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.request.CreateItineraryItemRequest;
import com.ishita.tripcoordinatorplatform.response.ConfirmDocumentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AttractionTourDocumentConfirmationService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservations;
    private final ItineraryItemService itineraryItems;
    private final ItineraryService itineraries;
    private final AttractionTourExtractionValidator validator;
    private final JsonMapper jsonMapper;
    private final TourConfirmationPolicy policy;
    private final TourCorrectionService corrections;
    @org.springframework.beans.factory.annotation.Autowired private TourBookingStateService bookingState;
    @org.springframework.beans.factory.annotation.Autowired private BookingConfirmationReceiptService receiptService;

    public AttractionTourDocumentConfirmationService(TravelDocumentRepository documents,
            TravelDocumentExtractionRepository extractions, ReservationRepository reservationRepository,
            ReservationService reservations, ItineraryItemService itineraryItems,
            ItineraryService itineraries,
            AttractionTourExtractionValidator validator, JsonMapper jsonMapper, TourConfirmationPolicy policy, TourCorrectionService corrections) {
        this.documents = documents;
        this.extractions = extractions;
        this.reservationRepository = reservationRepository;
        this.reservations = reservations;
        this.itineraryItems = itineraryItems;
        this.itineraries = itineraries;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
        this.policy=policy;this.corrections=corrections;
    }

    @Transactional
    public ConfirmDocumentResponse confirm(Long tripId, Long documentId, List<Long> participantIds) {
        return confirmReviewed(tripId,documentId,new com.ishita.tripcoordinatorplatform.request.ConfirmAttractionTourDocumentRequest(participantIds));
    }
    @Transactional
    public ConfirmDocumentResponse confirmReviewed(Long tripId,Long documentId,com.ishita.tripcoordinatorplatform.request.ConfirmAttractionTourDocumentRequest request) {
        var participantIds=request==null?null:request.participantIds();
        // Hold the document lock through all writes, including participant/conflict creation.
        // Concurrent confirmation or reprocess cannot create a second set of commitments.
        var document = documents.findForConfirmation(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document not found"));
        if (document.getDocumentType() != TravelDocumentType.ATTRACTION_TICKET
                && document.getDocumentType() != TravelDocumentType.TOUR_BOOKING) {
            throw TourCorrectionException.invalid("Travel document must be an attraction ticket or tour booking");
        }
        if (document.getProcessingStatus() == TravelDocumentProcessingStatus.PROCESSED) {
            throw TourCorrectionException.stale("Travel document has already been confirmed");
        }
        if (document.getProcessingStatus() != TravelDocumentProcessingStatus.REVIEW_REQUIRED) {
            throw TourCorrectionException.invalid("Travel document is not ready for confirmation");
        }
        var extraction = extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document extraction not found"));
        policy.requireLegacyExtraction(extraction.getExtractedData());
        if(new com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec(jsonMapper).schemaVersion(extraction.getExtractedData())==3
                && (request==null || !extraction.getId().equals(request.extractionId())))
            throw TourCorrectionException.stale("Identify the exact v3 extraction being confirmed.");
        boolean partial=bookingState!=null && bookingState.currentReading(extraction.getExtractedData());
        java.util.Set<Integer> selected=null;TourBookingStateService.Composition partialState=null;
        if(partial){
            var requested=request.selectedBookingIndexes();
            if(requested==null || requested.isEmpty() || requested.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(requested).size()!=requested.size())
                throw TourCorrectionException.validation("BOOKING_SELECTION_REQUIRED","Explicitly select one or more distinct original booking indices.");
            selected=new HashSet<>(requested);partialState=bookingState.compose(document,extraction,List.of());
            bookingState.requireToken(partialState,request.acceptedStateToken());bookingState.requireNotConfirmed(extraction,selected);
        }else {
            if(request!=null && (request.selectedBookingIndexes()!=null || request.acceptedStateToken()!=null))throw TourCorrectionException.validation("PARTIAL_CONFIRMATION_NOT_SUPPORTED","Explicit partial selection requires V3.4; legacy confirmation retains its existing contract.");
            if(request==null || request.correctionAcceptance()==null)policy.requireLifecycleConfirmation(extraction.getExtractedData());
        }
        AttractionTourExtractionResult proposal;
        try {
            new com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec(jsonMapper).decode(extraction.getExtractedData()).legacy();
        } catch (JacksonException | com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException exception) {
            throw TourCorrectionException.validation("SOURCE_PROPOSAL_INVALID","The stored proposal cannot be reviewed.");
        }
        TourCorrectionService.Accepted accepted=null;
        TourConfirmationPolicy.Effective effective;
        if(partial){
            if(request.correctionAcceptance()!=null)partialState=corrections.acceptForSelected(document,extraction,request.correctionAcceptance(),selected);
            corrections.verifyAcceptedSources(document,extraction,partialState,selected);
            effective=policy.validateSelected(partialState.facts(),TourConfirmationPolicy.correctedFields(partialState.changes()),selected);
        }else if(request!=null && request.correctionAcceptance()!=null){
            if(request.extractionId()==null || !request.extractionId().equals(extraction.getId()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Accept the exact reviewed extraction.");
            accepted=corrections.accept(document,extraction,request.correctionAcceptance());effective=accepted.effective();
        }else effective=policy.validate(policy.facts(extraction.getExtractedData()),Set.of());
        proposal=effective.proposal();
        var confirmationIndexes=effective.confirmationBookingIndexes().stream().sorted().toList();
        var allBookings=proposal.bookings();
        try {validator.validateForConfirmation(new AttractionTourExtractionResult(confirmationIndexes.stream().map(allBookings::get).toList()));}
        catch(com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException invalid){throw TourCorrectionException.validation("SOURCE_PROPOSAL_INVALID","The effective booking proposal is invalid.");}
        var arrivalReviews=new java.util.HashMap<Integer,com.ishita.tripcoordinatorplatform.request.ConfirmAttractionTourDocumentRequest.ArrivalReview>();
        if(request!=null && request.extractionId()!=null && !request.extractionId().equals(extraction.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"The extraction changed. Reopen review before confirming.");
        if(request!=null && request.arrivalReviews()!=null && !request.arrivalReviews().isEmpty()) {
            if(request.extractionId()==null) throw TourCorrectionException.invalid("Arrival review must identify the exact extraction.");
            for(var review:request.arrivalReviews()) {
                if(review==null || review.bookingIndex()==null || review.bookingIndex()<0 || review.bookingIndex()>=proposal.bookings().size() || !confirmationIndexes.contains(review.bookingIndex()) || review.requiredArrivalLocal()==null || arrivalReviews.putIfAbsent(review.bookingIndex(),review)!=null) throw TourCorrectionException.invalid("Supply one valid arrival review per booking.");
            }
        }
        var endingReviews=new java.util.HashMap<Integer,com.ishita.tripcoordinatorplatform.request.ActivityEndingLocationRequest>();
        if(request!=null && request.endingReviews()!=null && !request.endingReviews().isEmpty()) {
            if(request.extractionId()==null || !request.extractionId().equals(extraction.getId()))throw TourCorrectionException.invalid("Refresh the exact extraction before reviewing activity endpoints.");
            for(var ending:request.endingReviews()) {
                if(ending==null || ending.bookingIndex()==null || ending.bookingIndex()<0 || ending.bookingIndex()>=proposal.bookings().size() || !confirmationIndexes.contains(ending.bookingIndex()) || endingReviews.containsKey(ending.bookingIndex()))throw TourCorrectionException.invalid("Supply one valid ending review per booking.");
                var review=new com.ishita.tripcoordinatorplatform.request.ActivityEndingLocationRequest(ending.choice(),ending.location(),null,0L);
                try {review.validate();}catch(IllegalArgumentException invalid){throw TourCorrectionException.invalid("Invalid legacy endpoint review.");}
                if("SAME_AS_MEETING_POINT".equals(review.choice()) && proposal.bookings().get(ending.bookingIndex()).meetingPoint()==null)throw TourCorrectionException.invalid("The booking has no meeting point to confirm as its endpoint.");
                endingReviews.put(ending.bookingIndex(),review);
            }
        }
        var reviewedInstants=new java.util.HashMap<Integer,java.time.Instant>(effective.arrivals());
        // Legacy prefilled arrival is acceptance only when equivalent to the effective source requirement.
        // Changed facts cannot enter through this compatibility path.
        for(var entry:arrivalReviews.entrySet()) {
            int index=entry.getKey();var review=entry.getValue();var booking=proposal.bookings().get(index);
            if(booking.timezone()==null || review.timeZone()!=null && !booking.timezone().equals(review.timeZone()))throw TourCorrectionException.invalid("Timezone changes require an explicit source correction assessment.");
            var at=reviewedInstants.get(index);
            if(at==null || !review.requiredArrivalLocal().equals(java.time.LocalDateTime.ofInstant(at,java.time.ZoneId.of(booking.timezone()))))
                throw TourCorrectionException.validation("ARRIVAL_CORRECTION_REQUIRED","Changed required arrival needs an explicitly accepted source correction assessment.");
        }
        var confirmedEndpoints=new java.util.HashMap<Integer,TourConfirmationPolicy.Endpoint>();
        for(int index:confirmationIndexes)
            confirmedEndpoints.put(index,policy.endpointForConfirmation(effective,index,endingReviews.get(index)));


        var clarified=new java.util.HashSet<Integer>();
        if(request!=null && request.endpointClarifications()!=null && !request.endpointClarifications().isEmpty()){
            if(!extraction.getId().equals(request.extractionId()))throw TourCorrectionException.stale("Clarification must identify the current extraction.");
            for(var clarification:request.endpointClarifications()){
                if(clarification==null || clarification.bookingIndex()==null || clarification.bookingIndex()<0 || clarification.bookingIndex()>=proposal.bookings().size() || !confirmationIndexes.contains(clarification.bookingIndex()) || !clarified.add(clarification.bookingIndex()))throw TourCorrectionException.invalid("Identify one clarification per booking.");
                int index=clarification.bookingIndex();
                var original=policy.facts(extraction.getExtractedData()).path("bookings").get(index).path("endingPoint");
                boolean correctedToUnknown=effective.endpointCorrections().contains(index) && "UNKNOWN".equals(effective.endpoints().get(index).choice());
                // An explicitly accepted source correction may establish that an apparent endpoint was not supported.
                if(!original.isNull() && !original.isMissingNode() && !correctedToUnknown || effective.endpointCorrections().contains(index) && !correctedToUnknown || effective.endpoints().get(index).location()!=null)
                    throw TourCorrectionException.validation("ENDPOINT_CLARIFICATION_CONFLICT","A documented or corrected endpoint cannot be overwritten by a personal clarification.");
                var review=new com.ishita.tripcoordinatorplatform.request.ActivityEndingLocationRequest(clarification.choice(),clarification.location(),null,0L);
                try {review.validate();}catch(IllegalArgumentException invalid){throw TourCorrectionException.invalid("Invalid endpoint clarification.");}
                if(clarification.location()!=null && (clarification.location().getLatitude()!=null || clarification.location().getLongitude()!=null))throw TourCorrectionException.validation("UNVERIFIED_ENDPOINT_COORDINATES","Clarification accepts a description, not verified geographic coordinates.");
                var point="SAME_AS_MEETING_POINT".equals(clarification.choice())?proposal.bookings().get(index).meetingPoint():clarification.location();
                if("SAME_AS_MEETING_POINT".equals(clarification.choice()) && (point==null || (point.getName()==null || point.getName().isBlank()) && (point.getAddress()==null || point.getAddress().isBlank())))throw TourCorrectionException.validation("MISSING_MEETING_POINT","A meeting-point description is needed for Same.");
                var legacy=endingReviews.get(index);
                if(legacy!=null && (!legacy.choice().equals(clarification.choice()) || "LOCATION".equals(legacy.choice()) && !TourConfirmationPolicy.sameDocumentaryLocation(legacy.location(),point)))throw TourCorrectionException.validation("CONFLICTING_ENDPOINT_DECISIONS","Legacy review conflicts with endpoint clarification.");
                confirmedEndpoints.put(index,new TourConfirmationPolicy.Endpoint(clarification.choice(),TourConfirmationPolicy.documentaryLocation(point)));
            }
        }


        Set<String> references = new HashSet<>();
        for (int index:confirmationIndexes) {
            var booking=proposal.bookings().get(index);
            if (booking.bookingReference() != null && !booking.bookingReference().isBlank()
                    && (!references.add(booking.bookingReference())
                    || reservationRepository.existsByTrip_IdAndConfirmationNumber(tripId, booking.bookingReference()))) {
                throw TourCorrectionException.invalid("Reservation with this confirmation number already exists");
            }
        }

        // Older trips may predate automatic itinerary creation. Keep the repair in
        // this confirmation transaction so a failed save leaves no partial itinerary.
        itineraries.ensureItinerary(tripId);

        List<Long> reservationIds = new ArrayList<>();
        for (int bookingIndex : confirmationIndexes) {
            var booking = proposal.bookings().get(bookingIndex);
            var timing=effective.timings().get(bookingIndex);
            LocalDateTime start=timing.start();LocalDateTime end=timing.end();
            Reservation reservation = new Reservation();
            reservation.setType(ReservationType.ACTIVITY);
            reservation.setName(booking.name());
            reservation.setStartDateTime(start);
            reservation.setEndDateTime(end);
            reservation.setProvider(booking.provider());
            reservation.setStructuredLocation(TourConfirmationPolicy.documentaryLocation(booking.meetingPoint()));
            var endpoint=confirmedEndpoints.get(bookingIndex);
            reservation.reviewEndingLocation(new com.ishita.tripcoordinatorplatform.request.ActivityEndingLocationRequest(
                    endpoint.choice(),"LOCATION".equals(endpoint.choice())?endpoint.location():null,null,0L));
            reservation.recordConfirmedSchedule(booking.timezone(), extraction, bookingIndex);
            if(clarified.contains(bookingIndex))reservation.recordEndpointClarification(confirmedEndpoints.get(bookingIndex).choice(),document.getUploadedByUserId());
            var bookingReview=partial?bookingState.lastReview(partialState,bookingIndex):accepted==null?null:accepted.review();
            if(bookingReview!=null)reservation.recordCorrectionReview(bookingReview);
            if(reviewedInstants.containsKey(bookingIndex)) reservation.recordReviewedArrival(reviewedInstants.get(bookingIndex));
            if (booking.bookingReference() != null && !booking.bookingReference().isBlank()) {
                reservation.setConfirmationNumber(booking.bookingReference());
                reservation.setConfirmationNumberType(ReservationConfirmationType.BOOKING_ID);
            }
            Reservation saved = reservations.createReservation(tripId, reservation);
            reservationIds.add(saved.getId());

            CreateItineraryItemRequest item = new CreateItineraryItemRequest();
            item.setReservationId(saved.getId());
            item.setTitle(booking.name());
            item.setStartDateTime(start);
            item.setEndDateTime(end);
            item.setFlexibility(end == null ? ItineraryItemFlexibility.TIME_CONSTRAINED : ItineraryItemFlexibility.FIXED);
            item.setParticipantIds(participantIds);
            // Reuses SOLO owner assignment, GROUP validation and participant overlap detection.
            itineraryItems.createItineraryItem(tripId, item);
            if(partial)receiptService.record(new BookingConfirmationReceiptService.Record(tripId,documentId,document.getUploadedByUserId(),extraction.getId(),bookingIndex,saved.getId(),
                    TourCorrectionService.hash(extraction.getExtractedData()),bookingReview==null?null:bookingReview.id,bookingReview==null?null:bookingReview.version));
        }
        document.setProcessingStatus(!partial || bookingState.complete(partialState)?TravelDocumentProcessingStatus.PROCESSED:TravelDocumentProcessingStatus.REVIEW_REQUIRED);
        documents.save(document);
        return new ConfirmDocumentResponse(List.copyOf(reservationIds),confirmationIndexes,document.getProcessingStatus());
    }
}
