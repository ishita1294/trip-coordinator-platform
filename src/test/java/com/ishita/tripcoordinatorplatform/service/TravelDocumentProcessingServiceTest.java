package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentExtractionRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.storage.DocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TravelDocumentProcessingServiceTest {

    private final TravelDocumentRepository documents = mock(TravelDocumentRepository.class);
    private final TravelDocumentExtractionRepository extractions = mock(TravelDocumentExtractionRepository.class);
    private final DocumentClassifier classifier = mock(DocumentClassifier.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final FlightDocumentExtractor extractor = mock(FlightDocumentExtractor.class);
    private final TravelDocumentProcessingService service = new TravelDocumentProcessingService(
            documents, classifier, storage, extractor, new FlightExtractionValidator(), extractions,
            JsonMapper.builder().findAndAddModules().build());

    @Test
    void listsDocumentsUsingOnlyTheRequestedTripScope() {
        TravelDocument first = document(10L, 1L);
        TravelDocument second = document(20L, 2L);
        when(documents.findAllByTrip_IdOrderByUploadedAtDescIdDesc(1L)).thenReturn(List.of(first));
        when(documents.findAllByTrip_IdOrderByUploadedAtDescIdDesc(2L)).thenReturn(List.of(second));

        var result = service.getDocuments(1L);

        assertEquals(1, result.size());
        assertEquals(10L, result.getFirst().id());
        assertEquals(first.getOriginalFileName(), result.getFirst().originalFileName());
        assertEquals(first.getContentType(), result.getFirst().contentType());
        assertEquals(first.getProcessingStatus(), result.getFirst().processingStatus());
        assertEquals(first.getDocumentType(), result.getFirst().documentType());
        assertEquals(first.getUploadedAt(), result.getFirst().uploadedAt());
        verify(documents, never()).findAllByTrip_IdOrderByUploadedAtDescIdDesc(2L);
    }

    @Test
    void extractionListRejectsDocumentOutsideTripBeforeLoadingExtractions() {
        when(documents.findByIdAndTrip_Id(10L, 2L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.getDocumentExtractions(2L, 10L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(extractions);
    }

    @Test
    void extractionListReturnsExplicitIdsAndTimestampsWithoutParsingData() {
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(10L, 1L)));
        TravelDocumentExtraction newer = extraction(102L, Instant.parse("2026-01-02T00:00:00Z"));
        TravelDocumentExtraction older = extraction(101L, Instant.parse("2026-01-01T00:00:00Z"));
        when(extractions.findAllByTravelDocument_IdOrderByCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(newer, older));

        var result = service.getDocumentExtractions(1L, 10L);

        assertEquals(List.of(102L, 101L), result.stream().map(item -> item.extractionId()).toList());
        assertEquals(newer.getCreatedAt(), result.getFirst().createdAt());
        assertEquals(older.getCreatedAt(), result.getLast().createdAt());
    }

    @Test
    void flightProcessResponseContainsIdOfNewlySavedExtraction() {
        prepareProcessing(DocumentClassification.FLIGHT_CONFIRMATION);
        assertNull(documents.findByIdAndTrip_Id(10L, 1L).orElseThrow().getDocumentType());
        LocalDate date = LocalDate.now().plusDays(1);
        FlightExtractionResult result = new FlightExtractionResult(List.of(new FlightReservationExtraction(
                "ABC123", ConfirmationNumberType.PNR, List.of(new FlightSegmentExtraction(
                1, "BA215", "LHR", date, LocalTime.of(10, 0), "BOS", date, LocalTime.of(12, 0))))));
        when(extractor.extract(any())).thenAnswer(invocation -> {
            var document = documents.findByIdAndTrip_Id(10L, 1L).orElseThrow();
            assertEquals(TravelDocumentProcessingStatus.PROCESSING, document.getProcessingStatus());
            return result;
        });
        when(extractions.save(any())).thenAnswer(invocation -> {
            TravelDocumentExtraction saved = invocation.getArgument(0);
            saved.setId(103L);
            assertEquals(10L, saved.getTravelDocument().getId());
            assertNotNull(saved.getCreatedAt());
            assertEquals(TravelDocumentProcessingStatus.PROCESSING, saved.getTravelDocument().getProcessingStatus());
            return saved;
        });

        var response = service.processDocument(1L, 10L);

        assertEquals(10L, response.documentId());
        assertEquals(103L, response.extractionId());
        assertEquals(TravelDocumentType.FLIGHT_CONFIRMATION, response.documentType());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, response.processingStatus());
        verify(classifier).classify(any());
        verify(documents, times(2)).save(argThat(document ->
                document.getDocumentType() == TravelDocumentType.FLIGHT_CONFIRMATION
                        && document.getProcessingStatus() == TravelDocumentProcessingStatus.REVIEW_REQUIRED));
        verify(extractions).save(any());
        var order = inOrder(extractions, documents);
        order.verify(documents).save(any());
        order.verify(extractions).save(any());
        order.verify(documents).save(argThat(document ->
                document.getProcessingStatus() == TravelDocumentProcessingStatus.REVIEW_REQUIRED));
        verify(extractions, never()).delete(any());
        verify(extractions, never()).deleteAll();
    }

    @Test
    void classifiedFlightReprocessSkipsClassifierAndCreatesNewExtraction() {
        prepareProcessing(DocumentClassification.FLIGHT_CONFIRMATION);
        TravelDocument document = documents.findByIdAndTrip_Id(10L, 1L).orElseThrow();
        document.setDocumentType(TravelDocumentType.FLIGHT_CONFIRMATION);
        document.setProcessingStatus(TravelDocumentProcessingStatus.REVIEW_REQUIRED);
        TravelDocumentExtraction previous = extraction(102L, Instant.now());
        previous.setTravelDocument(document);
        String originalData = previous.getExtractedData();
        LocalDate date = LocalDate.now().plusDays(1);
        FlightExtractionResult result = new FlightExtractionResult(List.of(new FlightReservationExtraction(
                "ABC123", ConfirmationNumberType.PNR, List.of(new FlightSegmentExtraction(
                1, "BA215", "LHR", date, LocalTime.of(10, 0), "BOS", date, LocalTime.NOON)))));
        when(extractor.extract(any())).thenReturn(result);
        when(extractions.save(any())).thenAnswer(invocation -> {
            TravelDocumentExtraction saved = invocation.getArgument(0);
            assertNotSame(previous, saved);
            assertSame(document, saved.getTravelDocument());
            assertEquals(TravelDocumentProcessingStatus.PROCESSING, document.getProcessingStatus());
            saved.setId(103L);
            return saved;
        });

        var response = service.processDocument(1L, 10L);

        verifyNoInteractions(classifier);
        verify(extractor).extract(argThat(input -> input.originalFileName().equals("flight.pdf")
                && input.contentType().equals("application/pdf")
                && java.util.Arrays.equals(new byte[]{1, 2, 3}, input.content())));
        verify(extractions).save(any());
        assertEquals(103L, response.extractionId());
        assertEquals(TravelDocumentType.FLIGHT_CONFIRMATION, response.documentType());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, response.processingStatus());
        assertEquals(TravelDocumentProcessingStatus.REVIEW_REQUIRED, document.getProcessingStatus());
        assertEquals(originalData, previous.getExtractedData());
        verify(extractions, never()).delete(any());
        verify(extractions, never()).deleteAll();
    }

    @Test
    void classifiedFlightExtractionFailureStillMarksDocumentFailed() {
        prepareProcessing(DocumentClassification.FLIGHT_CONFIRMATION);
        TravelDocument document = documents.findByIdAndTrip_Id(10L, 1L).orElseThrow();
        document.setDocumentType(TravelDocumentType.FLIGHT_CONFIRMATION);
        document.setProcessingStatus(TravelDocumentProcessingStatus.REVIEW_REQUIRED);
        when(extractor.extract(any())).thenThrow(new IllegalStateException("Extraction failed"));

        assertThrows(IllegalStateException.class, () -> service.processDocument(1L, 10L));

        verifyNoInteractions(classifier, extractions);
        assertEquals(TravelDocumentType.FLIGHT_CONFIRMATION, document.getDocumentType());
        assertEquals(TravelDocumentProcessingStatus.FAILED, document.getProcessingStatus());
        verify(documents, times(2)).save(document);
    }

    @Test
    void nonFlightProcessResponseHasNoExtractionId() {
        prepareProcessing(DocumentClassification.HOTEL_BOOKING);
        var response = service.processDocument(1L, 10L);
        assertNull(response.extractionId());
        assertEquals(TravelDocumentType.HOTEL_BOOKING, response.documentType());
        assertEquals(TravelDocumentProcessingStatus.PROCESSING, response.processingStatus());
        verifyNoInteractions(extractor, extractions);
    }

    @Test
    void unsupportedProcessResponsePreservesFailedStatus() {
        prepareProcessing(DocumentClassification.UNSUPPORTED);
        var response = service.processDocument(1L, 10L);
        assertNull(response.extractionId());
        assertEquals(TravelDocumentProcessingStatus.FAILED, response.processingStatus());
        verifyNoInteractions(extractor, extractions);
    }

    @Test
    void storageFailureMarksDocumentFailedAndPreservesProviderMessage() {
        prepareProcessing(DocumentClassification.FLIGHT_CONFIRMATION);
        when(storage.open("file-key")).thenThrow(new IllegalStateException("Failed to open stored document"));
        var error = assertThrows(IllegalStateException.class, () -> service.processDocument(1L, 10L));
        assertEquals("Failed to open stored document", error.getMessage());
        assertEquals(TravelDocumentProcessingStatus.FAILED,
                documents.findByIdAndTrip_Id(10L, 1L).orElseThrow().getProcessingStatus());
        verifyNoInteractions(extractor, extractions);
    }

    @Test
    void currentExtractionUsesNewestLookupAndPreservesHistoryForProcessedDocument() {
        TravelDocument document = document(10L, 1L);
        document.setDocumentType(TravelDocumentType.FLIGHT_CONFIRMATION);
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document));
        Instant timestamp = Instant.parse("2026-01-02T00:00:00Z");
        TravelDocumentExtraction older = extraction(101L, timestamp);
        older.setExtractedData("{\"reservations\":[]}");
        TravelDocumentExtraction newest = extraction(102L, timestamp);
        newest.setExtractedData("{\"reservations\":[{\"confirmationNumber\":\"ABC123\",\"segments\":[]}]}");
        when(extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(10L))
                .thenReturn(Optional.of(newest));
        when(extractions.findByIdAndTravelDocument_Id(101L, 10L)).thenReturn(Optional.of(older));
        when(extractions.findAllByTravelDocument_IdOrderByCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(newest, older));

        var current = service.getCurrentDocumentExtraction(1L, 10L);

        assertEquals(102L, current.extractionId());
        assertEquals(10L, current.documentId());
        assertEquals(TravelDocumentType.FLIGHT_CONFIRMATION, current.documentType());
        assertEquals(timestamp, current.createdAt());
        assertEquals("ABC123", current.extractedData().path("reservations").get(0)
                .path("confirmationNumber").asString());
        assertEquals(101L, service.getDocumentExtraction(1L, 10L, 101L).extractionId());
        assertEquals(List.of(102L, 101L), service.getDocumentExtractions(1L, 10L).stream()
                .map(item -> item.extractionId()).toList());
        assertEquals(TravelDocumentProcessingStatus.PROCESSED, document.getProcessingStatus());
        verify(documents, never()).save(any());
        verify(extractions).findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(10L);
        verify(extractions, never()).save(any());
        verify(extractions, never()).delete(any());
        verify(extractions, never()).deleteAll();
    }

    @Test
    void currentExtractionRejectsDocumentOutsideTripBeforeLookingUpExtraction() {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.getCurrentDocumentExtraction(2L, 10L));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(extractions);
    }

    @Test
    void currentExtractionReturnsNotFoundWhenDocumentHasNoExtraction() {
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(10L, 1L)));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.getCurrentDocumentExtraction(1L, 10L));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void processedDocumentCannotBeReprocessed() {
        TravelDocument document = document(10L, 1L);
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSED);
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document));
        assertThrows(IllegalArgumentException.class, () -> service.processDocument(1L, 10L));
        assertEquals(TravelDocumentProcessingStatus.PROCESSED, document.getProcessingStatus());
        verify(documents, never()).save(any());
        verifyNoInteractions(storage, classifier, extractor, extractions);
    }

    @Test
    void malformedCurrentExtractionRemainsAnInternalDataError() {
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(10L, 1L)));
        when(extractions.findFirstByTravelDocument_IdOrderByCreatedAtDescIdDesc(10L))
                .thenReturn(Optional.of(extraction(102L, Instant.now())));
        assertThrows(IllegalStateException.class, () -> service.getCurrentDocumentExtraction(1L, 10L));
    }

    private void prepareProcessing(DocumentClassification classification) {
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(10L, 1L)));
        when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.open("file-key")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(classifier.classify(any())).thenReturn(classification);
    }

    private TravelDocument document(Long id, Long tripId) {
        Trip trip = new Trip();
        trip.setId(tripId);
        TravelDocument document = new TravelDocument();
        document.setId(id);
        document.setTrip(trip);
        document.setOriginalFileName("flight.pdf");
        document.setContentType("application/pdf");
        document.setStorageKey("file-key");
        document.setProcessingStatus(TravelDocumentProcessingStatus.UPLOADED);
        document.setUploadedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return document;
    }

    private TravelDocumentExtraction extraction(Long id, Instant createdAt) {
        TravelDocumentExtraction extraction = new TravelDocumentExtraction();
        extraction.setId(id);
        extraction.setCreatedAt(createdAt);
        extraction.setExtractedData("deliberately not JSON: metadata listing must not parse it");
        return extraction;
    }
}
