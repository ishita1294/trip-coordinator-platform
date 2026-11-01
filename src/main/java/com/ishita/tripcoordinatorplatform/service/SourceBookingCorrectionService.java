package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.Change;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Inactive native correction boundary. Intentionally not a bean/controller; no default routing or confirmation. */
@org.springframework.stereotype.Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SourceBookingCorrectionService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final TourCorrectionReviewRepository reviews;
    private final TourBookingStateService state;
    private final SourceBookingAcceptedReviewService nativeReview;
    private final AttractionTourDocumentExtractor assessor;
    private final JsonMapper mapper;
    public SourceBookingCorrectionService(TravelDocumentRepository documents,TravelDocumentExtractionRepository extractions,
            TourCorrectionReviewRepository reviews,TourBookingStateService state,SourceBookingAcceptedReviewService nativeReview,
            AttractionTourDocumentExtractor assessor,JsonMapper mapper) {
        this.documents=documents;this.extractions=extractions;this.reviews=reviews;this.state=state;
        this.nativeReview=nativeReview;this.assessor=assessor;this.mapper=mapper;
    }
    public record Acceptance(Long extractionId,long version) {}
    private record Bound(TravelDocument document,TravelDocumentExtraction extraction) {}
    private Bound bound(Long tripId,Long documentId,Long actor,Long extractionId) {
        var doc=documents.findForConfirmation(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(actor==null || !Objects.equals(actor,doc.getUploadedByUserId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(doc.getProcessingStatus()!=TravelDocumentProcessingStatus.REVIEW_REQUIRED
                || doc.getDocumentType()!=TravelDocumentType.TOUR_BOOKING && doc.getDocumentType()!=TravelDocumentType.ATTRACTION_TICKET)
            throw TourCorrectionException.invalid("Only unconfirmed attraction/tour documents support native correction.");
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId).orElseThrow(()->TourCorrectionException.stale("Source extraction unavailable."));
        if(!Objects.equals(extractionId,extraction.getId()) || extraction.getTravelDocument()==null
                || !Objects.equals(extraction.getTravelDocument().getId(),doc.getId()))throw TourCorrectionException.stale("Identify the exact current extraction.");
        return new Bound(doc,extraction);
    }
    private DocumentAiInput source(DocumentAiInput original) {
        // Only a future trusted storage orchestrator may supply these bytes; never a public request payload.
        if(original==null || original.content()==null || original.content().length==0 || original.content().length>20*1024*1024)
            throw TourCorrectionException.validation("INVALID_SOURCE_DOCUMENT","Original source unavailable or oversized.");
        return new DocumentAiInput(original.originalFileName(),original.contentType(),original.content().clone());
    }
    private void changes(TravelDocumentExtraction extraction,List<Change> changes) {
        if(changes==null || changes.isEmpty() || changes.size()>32)throw TourCorrectionException.invalid("Supply 1–32 correction changes.");
        var keys=new HashSet<String>();
        for(var c:changes)if(c==null || c.bookingIndex()==null || c.field()==null || !keys.add(c.bookingIndex()+":"+c.field()))
            throw TourCorrectionException.invalid("Invalid or duplicate booking change.");
        state.requireMutable(extraction,changes); // Existing receipts and pre-receipt reservations remain authoritative.
    }
    public record ScheduleRequest(Long extractionId,UUID requestId,String acceptedStateToken,Integer bookingIndex,tools.jackson.databind.JsonNode proposal) {}
    private void requireReadOnlyNative(TravelDocumentExtraction extraction,DocumentAiInput original) {
        if(!SourceBookingStoredV1.isStored(mapper.readTree(extraction.getExtractedData())))
            throw TourCorrectionException.invalid("This operation is only for native read-only evaluation documents.");
        SourceBookingStoredV1.read(extraction.getExtractedData(),mapper,original);
    }
    @Transactional(readOnly=true)
    public SourceBookingAcceptedReviewService.Review scheduleState(Long tripId,Long documentId,Long actor,DocumentAiInput original) {
        var source=source(original);var doc=documents.findByIdAndTrip_Id(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(actor==null || !Objects.equals(actor,doc.getUploadedByUserId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId).orElseThrow(()->TourCorrectionException.stale("Extraction unavailable."));
        requireReadOnlyNative(extraction,source);
        if(doc.getProcessingStatus()!=TravelDocumentProcessingStatus.REVIEW_REQUIRED)throw TourCorrectionException.stale("Only unconfirmed review documents support clarification.");
        return nativeReview.compose(doc,extraction,source,List.of());
    }
    /** Local validation of an explicit user declaration. Never calls the AI assessor. */
    @Transactional
    public TourCorrectionReview assessSchedule(Long tripId,Long documentId,Long actor,ScheduleRequest request,DocumentAiInput original) {
        if(request==null || request.requestId()==null || request.extractionId()==null || request.acceptedStateToken()==null
                || request.bookingIndex()==null || request.proposal()==null)throw TourCorrectionException.invalid("Identify extraction, booking, state token and request.");
        var source=source(original);var bound=bound(tripId,documentId,actor,request.extractionId());var extraction=bound.extraction();
        requireReadOnlyNative(extraction,source);var current=nativeReview.compose(bound.document(),extraction,source,List.of());
        var changes=List.of(new Change(request.bookingIndex(),NativeScheduleClarification.FIELD,mapper.createObjectNode(),request.proposal()));changes(extraction,changes);
        String payload=mapper.writeValueAsString(changes);var existing=reviews.findByDocumentIdAndActorIdAndRequestId(documentId,actor,request.requestId());
        if(existing.isPresent()) {
            var old=existing.get();var assessment=NativeScheduleClarification.assessment(mapper.readValue(old.assessmentJson,TourSourceAssessment.class),changes);
            if(!Objects.equals(old.extractionId,extraction.getId()) || !Objects.equals(old.extractionSha256,current.extractionSha256())
                    || !Objects.equals(old.documentSha256,current.original().sourceSha256()) || !Objects.equals(old.changesJson,payload)
                    || !Objects.equals(assessment.acceptedStateToken(),request.acceptedStateToken()))throw TourCorrectionException.stale("Request already bound to another clarification.");
            return old;
        }
        if(!Objects.equals(current.acceptedStateToken(),request.acceptedStateToken()))throw TourCorrectionException.stale("Accepted state changed; reload review.");
        nativeReview.compose(bound.document(),extraction,source,changes); // Proves bindings and arithmetic before persistence.
        var finding=new TourSourceAssessment.Finding(request.bookingIndex(),NativeScheduleClarification.FIELD,
                TourSourceAssessment.Outcome.USER_REPORTED_SCHEDULE_CLARIFICATION,"","User declaration","Not an AI assessment; original extraction unchanged.");
        var review=new TourCorrectionReview();review.documentId=documentId;review.extractionId=extraction.getId();review.actorId=actor;review.requestId=request.requestId();
        review.extractionSha256=current.extractionSha256();review.documentSha256=current.original().sourceSha256();review.changesJson=payload;
        review.assessmentJson=mapper.writeValueAsString(new TourSourceAssessment("USER_SCHEDULE_DECLARATION",List.of(finding),current.acceptedStateToken()));review.createdAt=Instant.now();
        return reviews.saveAndFlush(review);
    }
    @Transactional
    public SourceBookingAcceptedReviewService.Review acceptSchedule(Long tripId,Long documentId,Long actor,Long reviewId,Acceptance request,DocumentAiInput original) {
        if(request==null || request.extractionId()==null || reviewId==null || reviewId<=0)throw TourCorrectionException.invalid("Identify stored review and extraction.");
        var source=source(original);var bound=bound(tripId,documentId,actor,request.extractionId());requireReadOnlyNative(bound.extraction(),source);
        var stored=reviews.findById(reviewId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Objects.equals(stored.documentId,documentId) || !Objects.equals(stored.actorId,actor) || !Objects.equals(stored.extractionId,request.extractionId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var changes=state.changes(stored);
        if(changes.size()!=1 || !NativeScheduleClarification.FIELD.equals(changes.getFirst().field()))throw TourCorrectionException.invalid("Only schedule clarification is available here.");
        return accept(tripId,documentId,actor,reviewId,request,source);
    }
    /** Persists only a source assessment, not accepted corrections, reservations or extraction mutations. */
    @Transactional
    public TourCorrectionReview assess(Long tripId,Long documentId,Long actor,TourCorrectionRequest request,DocumentAiInput original) {
        if(request==null || request.requestId()==null || request.extractionId()==null)throw TourCorrectionException.invalid("Identify extraction and unique request.");
        var source=source(original);var bound=bound(tripId,documentId,actor,request.extractionId());
        var doc=bound.document();var extraction=bound.extraction();changes(extraction,request.changes());
        var current=nativeReview.compose(doc,extraction,source,List.of());
        var preview=nativeReview.compose(doc,extraction,source,request.changes());
        String payload=mapper.writeValueAsString(request.changes());
        var existing=reviews.findByDocumentIdAndActorIdAndRequestId(documentId,actor,request.requestId());
        if(existing.isPresent()) {
            var old=existing.get();
            if(!Objects.equals(old.extractionId,extraction.getId()) || !Objects.equals(old.extractionSha256,current.extractionSha256())
                    || !Objects.equals(old.documentSha256,current.original().sourceSha256()) || !Objects.equals(old.changesJson,payload))
                throw TourCorrectionException.stale("Request identifier already bound to different source or changes.");
            return old; // Same request never calls the assessor again; acceptance independently fences state.
        }
        var supported=new ArrayList<Change>();var findings=new ArrayList<TourSourceAssessment.Finding>();
        for(var c:request.changes()) {
            if(preview.bookings().get(c.bookingIndex()).eligibility().issues().stream().anyMatch(i->i.code().equals("TEMPORAL_CORRECTION_REQUIRES_NATIVE_CONSTRAINT_REVIEW")))
                findings.add(new TourSourceAssessment.Finding(c.bookingIndex(),c.field(),TourSourceAssessment.Outcome.UNRESOLVED,"","","Native temporal correction is not representable; no correction accepted."));
            else if(c.field().equals("lifecycle"))findings.add(new TourSourceAssessment.Finding(c.bookingIndex(),c.field(),
                    TourSourceAssessment.Outcome.USER_REPORTED_LIFECYCLE_CLARIFICATION,"","User declaration","User-reported lifecycle; source extraction unchanged."));
            else supported.add(c);
        }
        String assessmentModel="USER_DECLARATION";
        if(!supported.isEmpty()) {
            TourSourceAssessment assessment;
            try {assessment=TourCorrectionService.normalize(assessor.assessCorrections(source,extraction.getExtractedData(),supported),supported);}
            catch(com.openai.errors.OpenAIException | SourceAssessmentUnavailableException | InvalidDocumentExtractionException | UnsupportedOperationException unavailable) {
                assessment=TourCorrectionService.unresolved(supported,"Source assessment unavailable; no correction accepted.");
            }
            assessmentModel=assessment.model();findings.addAll(assessment.findings());
        }
        var normalized=TourCorrectionService.normalize(new TourSourceAssessment(assessmentModel,findings,current.acceptedStateToken()),request.changes());
        var review=new TourCorrectionReview();review.documentId=documentId;review.extractionId=extraction.getId();review.actorId=actor;review.requestId=request.requestId();
        review.extractionSha256=current.extractionSha256();review.documentSha256=current.original().sourceSha256();review.changesJson=payload;
        review.assessmentJson=mapper.writeValueAsString(normalized);review.createdAt=Instant.now();
        return reviews.saveAndFlush(review);
    }
    /** Versioned correction acceptance only. Confirmation remains disabled even for ready bookings. */
    @Transactional
    public SourceBookingAcceptedReviewService.Review accept(Long tripId,Long documentId,Long actor,Long reviewId,Acceptance request,DocumentAiInput original) {
        if(request==null || request.extractionId()==null || request.version()<0 || reviewId==null || reviewId<=0)
            throw TourCorrectionException.invalid("Identify stored review, extraction and current version.");
        var source=source(original);var bound=bound(tripId,documentId,actor,request.extractionId());
        var doc=bound.document();var extraction=bound.extraction();
        var review=org.hibernate.Hibernate.unproxy(reviews.findById(reviewId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)),TourCorrectionReview.class);
        var current=nativeReview.compose(doc,extraction,source,List.of());
        if(!Objects.equals(review.documentId,doc.getId()) || !Objects.equals(review.extractionId,extraction.getId()) || !Objects.equals(review.actorId,actor)
                || !Objects.equals(review.extractionSha256,current.extractionSha256()) || !Objects.equals(review.documentSha256,current.original().sourceSha256()))
            throw TourCorrectionException.stale("Review owner/extraction/source mismatch.");
        if(review.acceptedAt!=null) {
            if(!Objects.equals(review.acceptedBy,actor) || review.version!=request.version() && review.version!=request.version()+1)
                throw TourCorrectionException.stale("Accepted review version changed.");
            return current; // Idempotent replay does not create another journal transition.
        }
        if(review.version!=request.version())throw TourCorrectionException.stale("Review version changed.");
        var changes=state.changes(review);changes(extraction,changes);
        var assessment=NativeScheduleClarification.assessment(mapper.readValue(review.assessmentJson,TourSourceAssessment.class),changes);
        if(!Objects.equals(assessment.acceptedStateToken(),current.acceptedStateToken()))throw TourCorrectionException.stale("Accepted booking state changed; request a new assessment.");
        if(assessment.findings().stream().anyMatch(f->f.outcome()!=TourSourceAssessment.Outcome.SOURCE_SUPPORTED_BY_AI_REVIEW
                && f.outcome()!=TourSourceAssessment.Outcome.USER_REPORTED_LIFECYCLE_CLARIFICATION && f.outcome()!=TourSourceAssessment.Outcome.USER_REPORTED_SCHEDULE_CLARIFICATION))
            throw TourCorrectionException.validation("SOURCE_CORRECTION_NOT_SUPPORTED","Unresolved or contradicted corrections cannot be accepted.");
        var preview=nativeReview.compose(doc,extraction,source,changes);
        if(changes.stream().anyMatch(c->preview.bookings().get(c.bookingIndex()).eligibility().issues().stream()
                .anyMatch(i->i.code().equals("TEMPORAL_CORRECTION_REQUIRES_NATIVE_CONSTRAINT_REVIEW"))))
            throw TourCorrectionException.validation("SOURCE_CORRECTION_NOT_SUPPORTED","Unsupported native temporal correction.");
        var accepted=state.accepted(doc,extraction);var at=Instant.now().truncatedTo(ChronoUnit.MICROS);
        if(!accepted.isEmpty()&&!at.isAfter(accepted.getLast().acceptedAt))at=accepted.getLast().acceptedAt.plusNanos(1000);
        review.acceptedAt=at;review.acceptedBy=actor;reviews.saveAndFlush(review);
        return nativeReview.compose(doc,extraction,source,List.of());
    }
}
