package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus;
import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentProcessingOutboxPublisherTest {

    private final DocumentProcessingOutboxRepository outbox = mock(DocumentProcessingOutboxRepository.class);
    private final SqsClient sqs = mock(SqsClient.class);
    private final DocumentProcessingOutboxPublisher publisher = new DocumentProcessingOutboxPublisher(
            outbox, sqs, JsonMapper.builder().build(), "https://sqs.us-east-1.amazonaws.com/123456789012/jobs");

    @Test
    void sendsOnlyDocumentIdToConfiguredQueueBeforeMarkingPublished() {
        var entry = pending(1L, 19L);
        when(outbox.findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING))
                .thenReturn(List.of(entry));
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(invocation -> {
            SendMessageRequest request = invocation.getArgument(0);
            assertEquals("https://sqs.us-east-1.amazonaws.com/123456789012/jobs", request.queueUrl());
            assertEquals("{\"documentId\":19}", request.messageBody());
            assertEquals(DocumentProcessingOutboxStatus.PENDING, entry.getStatus());
            assertNull(entry.getPublishedAt());
            return null;
        });

        publisher.publishPending();

        assertEquals(DocumentProcessingOutboxStatus.PUBLISHED, entry.getStatus());
        assertNotNull(entry.getPublishedAt());
        var order = inOrder(sqs, outbox);
        order.verify(outbox).findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING);
        order.verify(sqs).sendMessage(any(SendMessageRequest.class));
        order.verify(outbox).save(entry);
    }

    @Test
    void failedSendRemainsPendingAndDoesNotPreventOtherRowsFromPublishing() {
        var failed = pending(1L, 19L);
        var successful = pending(2L, 20L);
        when(outbox.findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING))
                .thenReturn(List.of(failed, successful));
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("Network failure")).thenReturn(null);

        publisher.publishPending();

        assertEquals(DocumentProcessingOutboxStatus.PENDING, failed.getStatus());
        assertNull(failed.getPublishedAt());
        verify(outbox, never()).save(failed);
        assertEquals(DocumentProcessingOutboxStatus.PUBLISHED, successful.getStatus());
        verify(outbox).save(successful);
    }

    @Test
    void publishedRowsAreNotSentAgain() {
        var entry = pending(1L, 19L);
        when(outbox.findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING))
                .thenAnswer(invocation -> entry.getStatus() == DocumentProcessingOutboxStatus.PENDING
                        ? List.of(entry) : List.of());

        publisher.publishPending();
        publisher.publishPending();

        verify(sqs, times(1)).sendMessage(any(SendMessageRequest.class));
        verify(outbox, times(1)).save(entry);
        verify(outbox, times(2)).findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING);
    }

    @Test
    void persistenceFailureAfterSendIsLoggedAndDoesNotStopTheBatch() {
        var first = pending(1L, 19L);
        var second = pending(2L, 20L);
        when(outbox.findTop20ByStatusOrderByCreatedAtAscIdAsc(DocumentProcessingOutboxStatus.PENDING))
                .thenReturn(List.of(first, second));
        when(outbox.save(first)).thenThrow(new IllegalStateException("Database unavailable"));

        assertDoesNotThrow(publisher::publishPending);

        verify(sqs, times(2)).sendMessage(any(SendMessageRequest.class));
        verify(outbox).save(second);
    }

    private DocumentProcessingOutbox pending(Long id, Long documentId) {
        var entry = new DocumentProcessingOutbox();
        entry.setId(id);
        entry.setDocumentId(documentId);
        entry.setStatus(DocumentProcessingOutboxStatus.PENDING);
        return entry;
    }
}
