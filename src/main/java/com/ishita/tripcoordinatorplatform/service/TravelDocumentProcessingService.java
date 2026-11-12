package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentProcessingBudget;

import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import com.ishita.tripcoordinatorplatform.ai.document.InvalidDocumentExtractionException;
import com.ishita.tripcoordinatorplatform.ai.document.TourReadingValidator;
import com.ishita.tripcoordinatorplatform.ai.document.TourReading;
import com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec;
import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourDocumentExtractor;
import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourExtractionValidator;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassification;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassifier;
import com.ishita.tripcoordinatorplatform.ai.document.FlightDocumentExtractor;
import com.ishita.tripcoordinatorplatform.ai.document.FlightExtractionResult;
import com.ishita.tripcoordinatorplatform.ai.document.FlightExtractionValidator;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentExtraction;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentExtractionResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentProcessingResponse;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentSummaryResponse;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.Objects;
import java.util.List;
import static com.ishita.tripcoordinatorplatform.model.TravelDocumentType.TRAIN_TICKET;

@Service
public class TravelDocumentProcessingService {

    private final TravelDocumentRepository travelDocumentRepository;
    private final DocumentClassifier documentClassifier;
    private final DocumentStorage documentStorage;
    private final FlightDocumentExtractor flightDocumentExtractor;
    private final FlightExtractionValidator flightExtractionValidator;
    private final AttractionTourDocumentExtractor attractionTourDocumentExtractor;
    private final AttractionTourExtractionValidator attractionTourExtractionValidator;
    private final TravelDocumentExtractionRepository travelDocumentExtractionRepository;
    private final JsonMapper jsonMapper;
    private final DocumentProcessingFinalizationService finalizationService;
    @org.springframework.beans.factory.annotation.Autowired
    private DocumentProcessingOutboxRepository processingJobs;
    @org.springframework.beans.factory.annotation.Autowired
    private DocumentExtractionRoutingService extractionRouting;
    @org.springframework.beans.factory.annotation.Value("${app.document-intelligence.v3.workflow-enabled:false}")
    private boolean v3WorkflowEnabled;

    public TravelDocumentProcessingService(
            TravelDocumentRepository travelDocumentRepository,
            DocumentClassifier documentClassifier,
            DocumentStorage documentStorage,
            FlightDocumentExtractor flightDocumentExtractor,
            FlightExtractionValidator flightExtractionValidator,
            TravelDocumentExtractionRepository travelDocumentExtractionRepository,
            JsonMapper jsonMapper,
            DocumentProcessingFinalizationService finalizationService,
            AttractionTourDocumentExtractor attractionTourDocumentExtractor,
            AttractionTourExtractionValidator attractionTourExtractionValidator
    ) {
        this.travelDocumentRepository = travelDocumentRepository;
        this.documentClassifier = documentClassifier;
        this.documentStorage = documentStorage;
        this.flightDocumentExtractor = flightDocumentExtractor;
        this.flightExtractionValidator = flightExtractionValidator;
        this.travelDocumentExtractionRepository = travelDocumentExtractionRepository;
        this.jsonMapper = jsonMapper;
        this.finalizationService = finalizationService;
        this.attractionTourDocumentExtractor = attractionTourDocumentExtractor;
        this.attractionTourExtractionValidator = attractionTourExtractionValidator;
    }

    // Call only after a successful claim, using the exact token returned by that claim.
    // Storage, AI calls, validation, and serialization run outside the finalization transaction.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TravelDocumentProcessingResponse processClaimedDocument(Long documentId, UUID attemptId) {
        var document = travelDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document not found"));
        if (document.getProcessingStatus() != TravelDocumentProcessingStatus.PROCESSING
                || attemptId == null || !attemptId.equals(document.getProcessingAttemptId()))
            throw DocumentProcessingException.ownershipLost();
        String contract = null;
        if (document.getProcessingJobId() != null) {
            contract = processingJobs.findByDocumentIdAndProcessingJobId(documentId, document.getProcessingJobId())
                    .orElseThrow(() -> DocumentProcessingException.permanentInput("Processing job record is missing"))
                    .getExtractionContract();
        }
        TourExtractionCodec.Version version;
        try {
            version = TourExtractionCodec.Version.fromJobContract(contract);
        } catch (IllegalArgumentException unknown) {
            throw DocumentProcessingException.permanentInput("Unsupported extraction contract");
        }
        return processClaimedDocument(documentId, attemptId, version);
    }

    // Internal overload that takes the extraction version directly; normal workers resolve the durable job first.
    TravelDocumentProcessingResponse processClaimedDocument(Long documentId, UUID attemptId,
            TourExtractionCodec.Version version) {
        Objects.requireNonNull(attemptId, "Processing attempt ID is required");
        Objects.requireNonNull(version, "Extraction version is required");
        TravelDocument document = travelDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document not found"));
        if (document.getProcessingStatus() != TravelDocumentProcessingStatus.PROCESSING
                || !attemptId.equals(document.getProcessingAttemptId())) {
            throw DocumentProcessingException.ownershipLost();
        }

        if (document.getContentType() == null
                || !List.of("application/pdf", "image/jpeg", "image/png").contains(document.getContentType())) {
            throw DocumentProcessingException.permanentInput("Only PDF, JPEG, and PNG documents are supported");
        }
        if (version == TourExtractionCodec.Version.UNSUPPORTED)
            throw DocumentProcessingException.permanentInput("Extraction is not implemented for this document category");
        TravelDocumentType documentType = document.getDocumentType();
        String extractedData = null;
        TourReading readingResult = null;
        String sourceSha256 = null;
        String nativeResult=null;
        DocumentAiInput nativeInput=null;
        String stage = "storage";
        long stageStarted = System.nanoTime();
        long inputStarted = stageStarted;
        try (InputStream inputStream = documentStorage.open(document.getStorageKey())) {
            DocumentAiInput aiInput = new DocumentAiInput(document.getOriginalFileName(),
                    document.getContentType(), inputStream.readAllBytes());
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=storage elapsedMs={}",
                    documentId, (System.nanoTime() - inputStarted) / 1_000_000);
            if (aiInput.content().length == 0) {
                throw DocumentProcessingException.permanentInput("Stored document is empty and cannot be processed");
            }
            if (documentType == null) {
                stage = "classification";
                stageStarted = System.nanoTime();
                org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=classification status=started", documentId);
                long classificationStarted = stageStarted;
                documentType = toTravelDocumentType(documentClassifier.classify(aiInput));
                org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=classification elapsedMs={}",
                        documentId, (System.nanoTime() - classificationStarted) / 1_000_000);
            }
            if (version == TourExtractionCodec.Version.AUTO_V3_4 || version == TourExtractionCodec.Version.SOURCE_BOOKING_V1_REVIEW) {
                var decision = extractionRouting.resolve(documentId, document.getProcessingJobId(), attemptId, documentType);
                version = decision.contract();
                documentType = decision.documentType();
            }
            if (version == TourExtractionCodec.Version.UNSUPPORTED || documentType == null)
                throw DocumentProcessingException.permanentInput("Extraction is not implemented for this document category");
            stage = "extraction-validation";
            stageStarted = System.nanoTime();
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=extraction-validation status=started contract={}", documentId, version);
            long extractionStarted = stageStarted;
            if (documentType == TravelDocumentType.FLIGHT_CONFIRMATION) {
                if (version != TourExtractionCodec.Version.LEGACY && version != TourExtractionCodec.Version.FLIGHT_V1)
                    throw DocumentProcessingException.permanentInput("The selected tour contract does not support flight extraction");
                FlightExtractionResult result = flightDocumentExtractor.extract(aiInput);
                flightExtractionValidator.validate(result);
                extractedData = jsonMapper.writeValueAsString(result);
            } else if (documentType == TravelDocumentType.ATTRACTION_TICKET
                    || documentType == TravelDocumentType.TOUR_BOOKING) {
                if(version==TourExtractionCodec.Version.SOURCE_BOOKING_V1_REVIEW) {
                    nativeInput=new DocumentAiInput(aiInput.originalFileName(),aiInput.contentType(),aiInput.content().clone());
                    long providerStarted=System.nanoTime();
                    nativeResult=attractionTourDocumentExtractor.extractSourceBooking(nativeInput);
                    org.slf4j.LoggerFactory.getLogger(getClass()).info("Document native stage documentId={} jobId={} attemptId={} stage=extraction elapsedMs={}",documentId,document.getProcessingJobId(),attemptId,(System.nanoTime()-providerStarted)/1_000_000);
                    long validateStarted=System.nanoTime();
                    new com.ishita.tripcoordinatorplatform.ai.document.SourceBookingReader().read(nativeResult,jsonMapper,nativeInput);
                    org.slf4j.LoggerFactory.getLogger(getClass()).info("Document native stage documentId={} jobId={} attemptId={} stage=validation elapsedMs={}",documentId,document.getProcessingJobId(),attemptId,(System.nanoTime()-validateStarted)/1_000_000);
                } else if (version == TourExtractionCodec.Version.SEMANTIC_V3_4) {
                    if (!"tour-semantic-3.4".equals(TourReading.PROMPT_VERSION))
                        throw DocumentProcessingException.permanentInput("The saved V3.4 extraction contract is unavailable");
                    sourceSha256 = TourExtractionCodec.sourceSha256(aiInput.content());
                    readingResult = attractionTourDocumentExtractor.extractTourReading(aiInput);
                    if (readingResult == null || readingResult.provenance() == null
                            || !TourReading.PROMPT_VERSION.equals(readingResult.provenance().promptVersion()))
                        throw new InvalidDocumentExtractionException("Expected V3.4 extraction");
                    new TourReadingValidator().validate(readingResult, sourceSha256);
                } else if (version == TourExtractionCodec.Version.LEGACY) {
                    var result = attractionTourDocumentExtractor.extract(aiInput);
                    attractionTourExtractionValidator.validate(result);
                    extractedData = jsonMapper.writeValueAsString(result);
                } else {
                    throw DocumentProcessingException.permanentInput("The selected contract does not support tour extraction");
                }
            } else {
                throw DocumentProcessingException.permanentInput("Extraction is not implemented for this document type");
            }
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=extraction-validation elapsedMs={}",
                    documentId, (System.nanoTime() - extractionStarted) / 1_000_000);
        } catch (UnsupportedOperationException unsupported) {
            throw DocumentProcessingException.permanentInput("The configured adapter does not support the selected extraction contract");
        } catch (IOException exception) {
            // Preserve the cause for classification by the worker execution layer.
            throw new IllegalStateException("Failed to read stored document", exception);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Document stage documentId={} stage={} status=failed elapsedMs={} timeout={} causeClass={} validationDetail={}",
                    documentId, stage, (System.nanoTime() - stageStarted) / 1_000_000,
                    DocumentProcessingBudget.isTimeout(failure),
                    failure.getClass().getSimpleName(), InvalidDocumentExtractionException.validationDetail(failure));
            throw failure;
        }
        // Close the input before committing success, so a close failure cannot follow a successful commit.
        DocumentProcessingBudget.requireFinalizationTime();
        long finalizeStarted = System.nanoTime();
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=finalization status=started", documentId);
        TravelDocumentProcessingResponse result;
        try {
            if(nativeResult!=null) {
                result=finalizationService.finalizeSourceBooking(documentId,attemptId,documentType,nativeResult,nativeInput);
            } else if (readingResult != null) {
                result = finalizationService.finalizeTourReading(documentId, attemptId, documentType, readingResult, sourceSha256);
            } else {
                result = finalizationService.finalizeDocument(documentId, attemptId, documentType, extractedData);
            }
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Document stage documentId={} stage=finalization elapsedMs={}",
                    documentId, (System.nanoTime() - finalizeStarted) / 1_000_000);
            return result;
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Document stage documentId={} stage=finalization status=failed elapsedMs={} causeClass={} validationDetail={}",
                    documentId, (System.nanoTime() - finalizeStarted) / 1_000_000, failure.getClass().getSimpleName(),
                    InvalidDocumentExtractionException.validationDetail(failure));
            throw failure;
        }
    }

    public List<TravelDocumentSummaryResponse> getDocuments(Long tripId) {
        return travelDocumentRepository.findAllByTrip_IdOrderByUploadedAtDescIdDesc(tripId).stream()
                .map(TravelDocumentSummaryResponse::from)
                .toList();
    }

    public TravelDocumentExtractionResponse getCurrentDocumentExtraction(Long tripId, Long documentId) {
        return currentDocumentExtraction(tripId,documentId,v3WorkflowEnabled);
    }

    // Local integration only; ordinary controllers call the two-argument method above.
    TravelDocumentExtractionResponse getCurrentDocumentExtraction(Long tripId,Long documentId,TourConfirmationPolicy localPolicy){
        return currentDocumentExtraction(tripId,documentId,localPolicy.readingWorkflowEnabled());
    }

    private TravelDocumentExtractionResponse currentDocumentExtraction(Long tripId,Long documentId,boolean v3WorkflowEnabled){
        TravelDocument document = travelDocumentRepository.findByIdAndTrip_Id(documentId, tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document not found"));
        TravelDocumentExtraction extraction = travelDocumentExtractionRepository
                .findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Travel document extraction not found"));
        return extractionResponse(document, extraction,v3WorkflowEnabled);
    }

    private TravelDocumentExtractionResponse extractionResponse(TravelDocument document,TravelDocumentExtraction extraction,boolean v3WorkflowEnabled) {
        JsonNode extractedData;
        try {
            extractedData = jsonMapper.readTree(extraction.getExtractedData());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to parse stored extraction JSON", exception);
        }
        if (extractedData == null || extractedData.isMissingNode() || extractedData.isNull()) {
            throw new IllegalStateException("Stored extraction JSON is empty");
        }

        if(com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.isNativeVersion(extractedData)) {
            if(extraction.getTravelDocument()==null||!Objects.equals(document.getId(),extraction.getTravelDocument().getId()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            try(var input=documentStorage.open(document.getStorageKey())) {
                var original=new DocumentAiInput(document.getOriginalFileName(),document.getContentType(),input.readAllBytes());
                var stored=com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.read(extraction.getExtractedData(),jsonMapper,original);
                return new TravelDocumentExtractionResponse(extraction.getId(),document.getId(),document.getDocumentType(),extraction.getCreatedAt(),
                        extractedData,List.of(),false,null,stored.review(jsonMapper,original),stored.provenance());
            }catch(IOException unavailable){throw new IllegalStateException("Original source is unavailable for native review",unavailable);}
        }
        com.ishita.tripcoordinatorplatform.response.TourReadingReview semanticReview = null;
        if (document.getDocumentType() == TravelDocumentType.TOUR_BOOKING || document.getDocumentType() == TravelDocumentType.ATTRACTION_TICKET) {
            var decoded = new TourExtractionCodec(jsonMapper).decode(extraction.getExtractedData());
            if (decoded.reading() != null) {
                if (extraction.getTravelDocument() == null || !document.getId().equals(extraction.getTravelDocument().getId()))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Travel document extraction not found");
                semanticReview = com.ishita.tripcoordinatorplatform.response.TourReadingReview.fromReading(
                        document.getId(), extraction.getId(), decoded.reading(), v3WorkflowEnabled);
            }
        }
        return new TravelDocumentExtractionResponse(
                extraction.getId(),
                document.getId(),
                document.getDocumentType(),
                extraction.getCreatedAt(),
                extractedData,
                semanticReview == null ? com.ishita.tripcoordinatorplatform.intelligence.commitment.ConfirmedArrivalEvidence.review(extraction.getExtractedData()) : List.of(),
                semanticReview == null,
                semanticReview
        );
    }

    private TravelDocumentType toTravelDocumentType(
            DocumentClassification classification
    ) {
        return switch (classification) {
            case FLIGHT_CONFIRMATION ->
                    TravelDocumentType.FLIGHT_CONFIRMATION;
            case HOTEL_BOOKING ->
                    TravelDocumentType.HOTEL_BOOKING;
            case TRAIN_TICKET ->
                    TRAIN_TICKET;
            case ATTRACTION_TICKET ->
                    TravelDocumentType.ATTRACTION_TICKET;
            case TOUR_BOOKING ->
                    TravelDocumentType.TOUR_BOOKING;
            case RESTAURANT_RESERVATION ->
                    TravelDocumentType.RESTAURANT_RESERVATION;
            case UNSUPPORTED -> null;
        };
    }

}
