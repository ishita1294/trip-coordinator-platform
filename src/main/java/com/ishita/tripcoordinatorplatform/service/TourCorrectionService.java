package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.*;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;

@Service
public class TourCorrectionService {
    private final TravelDocumentRepository documents;
    private final TravelDocumentExtractionRepository extractions;
    private final TourCorrectionReviewRepository reviews;
    private final DocumentStorage storage;
    private final AttractionTourDocumentExtractor reader;
    private final TourConfirmationPolicy policy;
    private final JsonMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private TourBookingStateService bookingState;
    public TourCorrectionService(TravelDocumentRepository documents,TravelDocumentExtractionRepository extractions,
            TourCorrectionReviewRepository reviews,DocumentStorage storage,AttractionTourDocumentExtractor reader,
            TourConfirmationPolicy policy,JsonMapper mapper){
        this.documents=documents;this.extractions=extractions;this.reviews=reviews;this.storage=storage;this.reader=reader;this.policy=policy;this.mapper=mapper;
    }
    @Transactional(readOnly=true)
    public List<TourCorrectionReview> history(Long tripId,Long documentId,Long actor){
        if(!documents.existsByIdAndTrip_IdAndUploadedByUserId(documentId,tripId,actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return reviews.findAllByDocumentIdOrderByCreatedAtDescIdDesc(documentId);
    }
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static String hash(String value){return sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Transactional
    public TourCorrectionReview assess(Long tripId,Long documentId,Long actor,TourCorrectionRequest request){
        var document=documents.findForConfirmation(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Objects.equals(document.getUploadedByUserId(),actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(request==null || request.requestId()==null || request.extractionId()==null)throw TourCorrectionException.invalid("Identify the extraction and a unique correction request.");
        if(document.getProcessingStatus()!=TravelDocumentProcessingStatus.REVIEW_REQUIRED || document.getDocumentType()!=TravelDocumentType.TOUR_BOOKING && document.getDocumentType()!=TravelDocumentType.ATTRACTION_TICKET)throw TourCorrectionException.invalid("Only unconfirmed attraction/tour documents support reading corrections; amendments are separate.");
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId)
                .orElseThrow(()->new TourCorrectionException(HttpStatus.NOT_FOUND,"SOURCE_PROPOSAL_MISSING","The source proposal is unavailable; reload document review."));
        if(!extraction.getId().equals(request.extractionId()))throw TourCorrectionException.stale("Extraction changed; reload review.");
        policy.requireLegacyExtraction(extraction.getExtractedData());
        boolean partial=bookingState!=null && bookingState.currentV34(extraction.getExtractedData());
        boolean lifecycle=TourConfirmationPolicy.hasLifecycleClarification(request.changes());
        if(request.changes()==null || request.changes().isEmpty() || request.changes().size()>32)throw TourCorrectionException.invalid("Supply 1–32 correction changes.");
        var distinct=new HashSet<String>();for(var change:request.changes())if(change==null || !distinct.add(change.bookingIndex()+":"+change.field()))throw TourCorrectionException.invalid("Invalid or duplicate booking change.");
        TourBookingStateService.Composition state=null;
        if(partial){bookingState.requireMutable(extraction,request.changes());state=bookingState.compose(document,extraction,request.changes());}
        if(!partial && !lifecycle)policy.requireLifecycleConfirmation(extraction.getExtractedData());
        var proposed=partial?state.facts():policy.proposed(extraction.getExtractedData(),request.changes());
        var sourceChanges=request.changes().stream().filter(c->!c.field().equals("lifecycle")).toList();
        if(partial){
            if(!sourceChanges.isEmpty())policy.validateSelected(proposed,TourConfirmationPolicy.correctedFields(state.changes()),sourceChanges.stream().map(TourCorrectionRequest.Change::bookingIndex).collect(java.util.stream.Collectors.toSet()));
        }else if(!lifecycle || !sourceChanges.isEmpty())policy.validate(proposed,TourConfirmationPolicy.correctedFields(request.changes()));
        else policy.preview(extraction.getExtractedData(),request.changes(),extraction.getId());
        String payload=mapper.writeValueAsString(request.changes());
        var existing=reviews.findByDocumentIdAndActorIdAndRequestId(documentId,actor,request.requestId());
        if(existing.isPresent()){
            var old=existing.get();
            if(!old.extractionId.equals(extraction.getId()) || !old.extractionSha256.equals(hash(extraction.getExtractedData())) || !old.changesJson.equals(payload))throw TourCorrectionException.stale("Correction request identifier was already used for different facts.");
            return withPreview(old,extraction.getExtractedData(),request.changes());
        }
        var review=new TourCorrectionReview();review.documentId=documentId;review.extractionId=extraction.getId();review.actorId=actor;review.requestId=request.requestId();
        review.extractionSha256=hash(extraction.getExtractedData());review.changesJson=payload;review.createdAt=Instant.now();
        TourSourceAssessment assessment;
        try(var stream=openOriginal(document)){
            byte[] bytes=stream.readNBytes(20*1024*1024+1);
            if(bytes.length==0 || bytes.length>20*1024*1024)throw TourCorrectionException.validation("INVALID_SOURCE_DOCUMENT","Original document is empty or exceeds the review limit.");
            review.documentSha256=sha(bytes);
            policy.requireMatchingSource(extraction.getExtractedData(),review.documentSha256);
            TourSourceAssessment sourceAssessment;
            if(sourceChanges.isEmpty())sourceAssessment=new TourSourceAssessment("USER_DECLARATION",List.of());
            else try {
                sourceAssessment=normalize(reader.assessCorrections(new DocumentAiInput(document.getOriginalFileName(),document.getContentType(),bytes),extraction.getExtractedData(),sourceChanges),sourceChanges);
            }catch(com.openai.errors.OpenAIException | SourceAssessmentUnavailableException | InvalidDocumentExtractionException | UnsupportedOperationException unavailable){sourceAssessment=unresolved(sourceChanges,"Source assessment unavailable; no correction accepted.");}
            var findings=new ArrayList<>(sourceAssessment.findings());
            for(var change:request.changes())if(change.field().equals("lifecycle"))findings.add(new TourSourceAssessment.Finding(change.bookingIndex(),change.field(),
                    TourSourceAssessment.Outcome.USER_REPORTED_LIFECYCLE_CLARIFICATION,"","User declaration for booking "+(change.bookingIndex()+1),
                    "User-reported status; source evidence has not been reinterpreted or independently verified."));
            assessment=normalize(new TourSourceAssessment(sourceAssessment.model(),findings),request.changes());
        }catch(java.io.IOException | software.amazon.awssdk.core.exception.SdkException unavailable){throw unavailableOriginal();}
        if(partial)assessment=new TourSourceAssessment(assessment.model(),assessment.findings(),state.token());
        review.assessmentJson=mapper.writeValueAsString(assessment);
        return withPreview(reviews.saveAndFlush(review),extraction.getExtractedData(),request.changes());
    }
    private TourCorrectionReview withPreview(TourCorrectionReview review,String source,List<TourCorrectionRequest.Change> changes){
        if(new TourExtractionCodec(mapper).schemaVersion(source)==3){
            if(bookingState!=null && bookingState.currentV34(source)){
                var document=documents.findById(review.documentId).orElseThrow();var extraction=extractions.findById(review.extractionId).orElseThrow();
                review.effectivePreview=bookingState.preview(document,bookingState.compose(document,extraction,changes));
            }else review.effectivePreview=policy.preview(source,changes,review.extractionId);
        }
        return review;
    }
    static TourSourceAssessment unresolved(List<TourCorrectionRequest.Change> changes,String reason){return new TourSourceAssessment("UNAVAILABLE",changes.stream().map(c->new TourSourceAssessment.Finding(c.bookingIndex(),c.field(),TourSourceAssessment.Outcome.UNRESOLVED,"","",reason)).toList());}
    static TourSourceAssessment normalize(TourSourceAssessment result,List<TourCorrectionRequest.Change> changes){
        if(result==null || result.model()==null || result.findings()==null || result.findings().size()!=changes.size())return unresolved(changes,"Incomplete source assessment.");
        var keys=new HashSet<String>();
        for(var finding:result.findings()){
            if(finding==null || finding.outcome()==null || !keys.add(finding.bookingIndex()+":"+finding.field()) || changes.stream().noneMatch(c->c.bookingIndex()==finding.bookingIndex() && c.field().equals(finding.field())))return unresolved(changes,"Ambiguous booking/field association.");
        }
        return new TourSourceAssessment(result.model(),result.findings().stream().map(f-> {
            boolean declared="lifecycle".equals(f.field());
            if(declared != (f.outcome()==TourSourceAssessment.Outcome.USER_REPORTED_LIFECYCLE_CLARIFICATION))
                return new TourSourceAssessment.Finding(f.bookingIndex(),f.field(),TourSourceAssessment.Outcome.UNRESOLVED,"","","Invalid assessment provenance.");
            boolean conclusive=f.outcome()==TourSourceAssessment.Outcome.SOURCE_SUPPORTED_BY_AI_REVIEW || f.outcome()==TourSourceAssessment.Outcome.SOURCE_CONTRADICTED;
            if(conclusive && (f.sourceExcerpt()==null || f.sourceExcerpt().isBlank() || f.bookingAssociation()==null || f.bookingAssociation().isBlank() || (f.uncertainty()==null || !f.uncertainty().isBlank())))return new TourSourceAssessment.Finding(f.bookingIndex(),f.field(),TourSourceAssessment.Outcome.UNRESOLVED,f.sourceExcerpt(),f.bookingAssociation(),"Insufficient or uncertain AI support.");
            return f;
        }).toList(),result.acceptedStateToken());
    }
    public record Accepted(TourConfirmationPolicy.Effective effective,TourCorrectionReview review) {}
    @Transactional
    public Accepted accept(TravelDocument document,TravelDocumentExtraction extraction,ConfirmAttractionTourDocumentRequest.CorrectionAcceptance acceptance){
        var review=checkedReview(document,extraction,acceptance,false);
        var changes=Arrays.asList(mapper.readValue(review.changesJson,TourCorrectionRequest.Change[].class));
        var effective=policy.validate(policy.proposed(extraction.getExtractedData(),changes),TourConfirmationPolicy.correctedFields(changes));
        markAccepted(document,extraction,review);
        return new Accepted(effective,review);
    }
    private TourCorrectionReview checkedReview(TravelDocument document,TravelDocumentExtraction extraction,ConfirmAttractionTourDocumentRequest.CorrectionAcceptance acceptance,boolean allowAccepted){
        if(acceptance.reviewId()==null || acceptance.reviewId()<=0 || acceptance.version()<0)throw TourCorrectionException.invalid("Identify a stored assessment and its current version.");
        var review=org.hibernate.Hibernate.unproxy(reviews.findById(acceptance.reviewId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)),TourCorrectionReview.class);
        if(!review.documentId.equals(document.getId()) || !review.actorId.equals(document.getUploadedByUserId()) || !review.extractionId.equals(extraction.getId()) || !review.extractionSha256.equals(hash(extraction.getExtractedData())) || (!allowAccepted || review.acceptedAt==null ? review.version!=acceptance.version() || review.acceptedAt!=null : review.version!=acceptance.version() && review.version!=acceptance.version()+1))throw TourCorrectionException.stale("Correction assessment is stale or already accepted.");
        List<TourCorrectionRequest.Change> changes=Arrays.asList(mapper.readValue(review.changesJson,TourCorrectionRequest.Change[].class));
        var assessment=normalize(mapper.readValue(review.assessmentJson,TourSourceAssessment.class),changes);
        if(assessment.findings().stream().anyMatch(f->f.outcome()!=TourSourceAssessment.Outcome.SOURCE_SUPPORTED_BY_AI_REVIEW && f.outcome()!=TourSourceAssessment.Outcome.USER_REPORTED_LIFECYCLE_CLARIFICATION))throw TourCorrectionException.validation("SOURCE_CORRECTION_NOT_SUPPORTED","Contradicted or unresolved corrections cannot be confirmed.");
        checkReviewSource(document,extraction,review);
        return review;
    }
    private void checkReviewSource(TravelDocument document,TravelDocumentExtraction extraction,TourCorrectionReview review){
        try(var stream=openOriginal(document)){
            var bytes=stream.readNBytes(20*1024*1024+1);
            if(!review.documentSha256.equals(sha(bytes)))throw TourCorrectionException.stale("Original source changed; request a fresh assessment.");
            policy.requireMatchingSource(extraction.getExtractedData(),sha(bytes));
        }catch(java.io.IOException | software.amazon.awssdk.core.exception.SdkException failure){throw unavailableOriginal();}
    }
    void verifyAcceptedSources(TravelDocument document,TravelDocumentExtraction extraction,TourBookingStateService.Composition state,Set<Integer> selected){
        var used=new HashSet<Long>();
        for(var review:state.accepted())if(bookingState.changes(review).stream().anyMatch(c->selected.contains(c.bookingIndex())) && used.add(review.id))
            checkReviewSource(document,extraction,review);
    }
    private void markAccepted(TravelDocument document,TravelDocumentExtraction extraction,TourCorrectionReview review){
        var latest=bookingState==null?List.<TourCorrectionReview>of():bookingState.accepted(document,extraction);
        var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(!latest.isEmpty() && !at.isAfter(latest.getLast().acceptedAt))at=latest.getLast().acceptedAt.plusNanos(1000);
        review.acceptedAt=at;review.acceptedBy=document.getUploadedByUserId();reviews.saveAndFlush(review);
    }
    public record LifecycleAcceptance(Long extractionId,long version) {}
    @Transactional
    public TourBookingStateService.Snapshot acceptLifecycle(Long tripId,Long documentId,Long actor,Long reviewId,LifecycleAcceptance request){
        var document=documents.findForConfirmation(documentId,tripId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Objects.equals(document.getUploadedByUserId(),actor))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var extraction=extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId).orElseThrow(()->TourCorrectionException.stale("Extraction unavailable."));
        bookingState.requireCurrent(extraction.getExtractedData());
        if(request==null || !Objects.equals(extraction.getId(),request.extractionId()))throw TourCorrectionException.stale("Identify the exact extraction.");
        var acceptance=new ConfirmAttractionTourDocumentRequest.CorrectionAcceptance(reviewId,request.version());
        var review=checkedReview(document,extraction,acceptance,true);var changes=bookingState.changes(review);
        if(changes.stream().anyMatch(c->!c.field().equals("lifecycle")))throw TourCorrectionException.invalid("Independent acceptance is limited to lifecycle-only clarification reviews.");
        if(review.acceptedAt!=null)return bookingState.snapshot(document,bookingState.compose(document,extraction,List.of()));
        if(document.getProcessingStatus()!=TravelDocumentProcessingStatus.REVIEW_REQUIRED)throw TourCorrectionException.stale("Document is not awaiting review.");
        preparePartialAcceptance(document,extraction,review);
        markAccepted(document,extraction,review);
        var state=bookingState.compose(document,extraction,List.of());
        if(bookingState.complete(state)){document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);documents.save(document);}
        return bookingState.snapshot(document,state);
    }
    private TourBookingStateService.Composition preparePartialAcceptance(TravelDocument document,TravelDocumentExtraction extraction,TourCorrectionReview review){
        var current=bookingState.compose(document,extraction,List.of());
        var assessment=mapper.readValue(review.assessmentJson,TourSourceAssessment.class);
        bookingState.requireToken(current,assessment.acceptedStateToken());
        var changes=bookingState.changes(review);bookingState.requireMutable(extraction,changes);
        return bookingState.compose(document,extraction,changes);
    }
    @Transactional
    public TourBookingStateService.Composition acceptForSelected(TravelDocument document,TravelDocumentExtraction extraction,ConfirmAttractionTourDocumentRequest.CorrectionAcceptance acceptance,Set<Integer> selected){
        var review=checkedReview(document,extraction,acceptance,true);
        if(review.acceptedAt!=null)return bookingState.compose(document,extraction,List.of());
        var state=preparePartialAcceptance(document,extraction,review);
        policy.validateSelected(state.facts(),TourConfirmationPolicy.correctedFields(state.changes()),selected);
        markAccepted(document,extraction,review);
        return bookingState.compose(document,extraction,List.of());
    }
    private java.io.InputStream openOriginal(TravelDocument document){
        try{return storage.open(document.getStorageKey());}
        catch(com.ishita.tripcoordinatorplatform.storage.DocumentReadException failure){
            if(failure.reason()==com.ishita.tripcoordinatorplatform.storage.DocumentReadException.Reason.MISSING)
                throw new TourCorrectionException(HttpStatus.NOT_FOUND,"ORIGINAL_DOCUMENT_MISSING","The original document is missing; no correction was applied.");
            if(failure.reason()==com.ishita.tripcoordinatorplatform.storage.DocumentReadException.Reason.ACCESS_DENIED)
                throw new TourCorrectionException(HttpStatus.SERVICE_UNAVAILABLE,"ORIGINAL_DOCUMENT_INACCESSIBLE","The original document cannot currently be accessed; no correction was applied.");
            throw unavailableOriginal();
        }catch(IllegalStateException legacyStorageFailure){
            // Compatibility only at the documented storage.open boundary, never a global exception mapper.
            throw unavailableOriginal();
        }
    }
    private static TourCorrectionException unavailableOriginal(){return new TourCorrectionException(HttpStatus.SERVICE_UNAVAILABLE,"ORIGINAL_DOCUMENT_UNAVAILABLE","The original document is temporarily unavailable; no correction was applied.");}
}
