package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentProcessingWorkerClaimServiceTest {

    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");
    private final TravelDocumentRepository documents = mock(TravelDocumentRepository.class);
    private final DocumentProcessingWorkerClaimService service = new DocumentProcessingWorkerClaimService(
            documents, 120, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void successfulQueuedClaimReturnsClaimedWithoutReclaimOrRead() {
        when(documents.claimQueuedForProcessing(10L, now)).thenReturn(1);

        assertEquals(DocumentProcessingWorkerClaimResult.CLAIMED, service.claim(10L));

        verify(documents).claimQueuedForProcessing(10L, now);
        verifyNoMoreInteractions(documents);
    }

    @Test
    void freshProcessingLeaseReturnsAlreadyActive() {
        TravelDocument document = new TravelDocument();
        document.setProcessingStatus(TravelDocumentProcessingStatus.PROCESSING);
        document.setProcessingStartedAt(now.minusSeconds(119));
        when(documents.findById(10L)).thenReturn(Optional.of(document));

        assertEquals(DocumentProcessingWorkerClaimResult.ALREADY_ACTIVE, service.claim(10L));

        verify(documents).reclaimStaleProcessing(10L, now, now.minusSeconds(120));
    }

    @Test
    void successfulStaleReclaimUsesLeaseCutoffAndReturnsClaimed() {
        when(documents.reclaimStaleProcessing(10L, now, now.minusSeconds(120))).thenReturn(1);

        assertEquals(DocumentProcessingWorkerClaimResult.CLAIMED, service.claim(10L));

        var order = inOrder(documents);
        order.verify(documents).claimQueuedForProcessing(10L, now);
        order.verify(documents).reclaimStaleProcessing(10L, now, now.minusSeconds(120));
        verifyNoMoreInteractions(documents);
    }

    @ParameterizedTest
    @EnumSource(value = TravelDocumentProcessingStatus.class, names = {"UPLOADED", "REVIEW_REQUIRED", "PROCESSED", "FAILED"})
    void otherStatesAreNotProcessable(TravelDocumentProcessingStatus status) {
        TravelDocument document = new TravelDocument();
        document.setProcessingStatus(status);
        when(documents.findById(10L)).thenReturn(Optional.of(document));

        assertEquals(DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE, service.claim(10L));
    }

    @Test
    void missingDocumentIsNotProcessable() {
        assertEquals(DocumentProcessingWorkerClaimResult.NOT_PROCESSABLE, service.claim(10L));
    }

    @Test
    void configuredLeaseChangesStaleCutoff() {
        var custom = new DocumentProcessingWorkerClaimService(documents, 300, Clock.fixed(now, ZoneOffset.UTC));

        custom.claim(10L);

        verify(documents).reclaimStaleProcessing(10L, now, now.minusSeconds(300));
    }

    @Test
    void rejectsNonPositiveLease() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentProcessingWorkerClaimService(documents, 0));
    }

    @Test
    void delegatesCompletedLeaseCleanupToConditionalUpdate() {
        service.clearCompletedLease(10L);

        verify(documents).clearCompletedProcessingLease(10L);
        verifyNoMoreInteractions(documents);
    }
}
