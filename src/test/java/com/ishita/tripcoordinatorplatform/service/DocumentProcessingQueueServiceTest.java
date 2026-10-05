package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutboxStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentProcessingQueueServiceTest {

    private final TravelDocumentRepository documents = mock(TravelDocumentRepository.class);
    private final DocumentProcessingOutboxRepository outbox = mock(DocumentProcessingOutboxRepository.class);
    private final DocumentProcessingQueueService service = new DocumentProcessingQueueService(documents, outbox);
    private final List<TravelDocumentProcessingStatus> allowed = List.of(
            TravelDocumentProcessingStatus.UPLOADED,
            TravelDocumentProcessingStatus.REVIEW_REQUIRED,
            TravelDocumentProcessingStatus.FAILED);

    @ParameterizedTest
    @EnumSource(value = TravelDocumentProcessingStatus.class, names = {"UPLOADED", "REVIEW_REQUIRED", "FAILED"})
    void queuesAllowedStatusAndCreatesExactlyOnePendingEntry(TravelDocumentProcessingStatus initial) {
        TravelDocument document = document(initial);
        when(documents.claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed))
                .thenAnswer(invocation -> {
                    document.setProcessingStatus(TravelDocumentProcessingStatus.QUEUED);
                    return 1;
                });
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document));

        var response = service.queueDocument(1L, 10L);

        assertEquals(10L, response.documentId());
        assertEquals(TravelDocumentProcessingStatus.QUEUED, response.processingStatus());
        assertEquals(TravelDocumentType.FLIGHT_CONFIRMATION, response.documentType());
        assertNull(response.extractionId());
        var order = inOrder(documents, outbox);
        order.verify(documents).claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed);
        order.verify(documents).findByIdAndTrip_Id(10L, 1L);
        order.verify(outbox).save(argThat(entry -> entry.getDocumentId().equals(10L)
                && entry.getStatus() == DocumentProcessingOutboxStatus.PENDING
                && entry.getCreatedAt() != null && entry.getPublishedAt() == null));
        verify(documents, never()).save(any());
        verifyNoMoreInteractions(outbox);
    }

    @ParameterizedTest
    @EnumSource(value = TravelDocumentProcessingStatus.class, names = {"QUEUED", "PROCESSING", "PROCESSED"})
    void rejectsDisallowedStatusWithoutCreatingOutboxEntry(TravelDocumentProcessingStatus status) {
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(status)));

        var error = assertThrows(ResponseStatusException.class, () -> service.queueDocument(1L, 10L));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("Travel document cannot be queued in its current status", error.getReason());

        verify(documents).claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed);
        verifyNoInteractions(outbox);
    }

    @Test
    void missingDocumentOrWrongTripReturnsNotFoundWithoutOutboxEntry() {
        when(documents.findByIdAndTrip_Id(10L, 2L)).thenReturn(Optional.empty());

        var error = assertThrows(ResponseStatusException.class, () -> service.queueDocument(2L, 10L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(documents).claimForQueue(10L, 2L, TravelDocumentProcessingStatus.QUEUED, allowed);
        verifyNoInteractions(outbox);
    }

    @Test
    void lostClaimDoesNotInsertEvenIfReadShowsAnAllowedStatus() {
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(
                TravelDocumentProcessingStatus.REVIEW_REQUIRED)));

        var error = assertThrows(ResponseStatusException.class, () -> service.queueDocument(1L, 10L));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());

        verifyNoInteractions(outbox);
    }

    @Test
    void outboxFailurePropagatesToTransactionalCaller() {
        when(documents.claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed)).thenReturn(1);
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(
                TravelDocumentProcessingStatus.QUEUED)));
        RuntimeException failure = new IllegalStateException("Database insert failed");
        when(outbox.save(any(DocumentProcessingOutbox.class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.queueDocument(1L, 10L)));
    }

    @Test
    void transactionCommitsOnlyAfterClaimAndOutboxInsert() {
        when(documents.claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed)).thenReturn(1);
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(
                TravelDocumentProcessingStatus.QUEUED)));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);

        transactionalService(transactions).queueDocument(1L, 10L);

        var order = inOrder(transactions, documents, outbox);
        order.verify(transactions).getTransaction(any());
        order.verify(documents).claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed);
        order.verify(documents).findByIdAndTrip_Id(10L, 1L);
        order.verify(outbox).save(any(DocumentProcessingOutbox.class));
        order.verify(transactions).commit(transaction);
        verify(transactions, never()).rollback(any());
    }

    @Test
    void transactionRollsBackWhenOutboxInsertFails() {
        when(documents.claimForQueue(10L, 1L, TravelDocumentProcessingStatus.QUEUED, allowed)).thenReturn(1);
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document(
                TravelDocumentProcessingStatus.QUEUED)));
        when(outbox.save(any(DocumentProcessingOutbox.class))).thenThrow(new IllegalStateException("Insert failed"));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(transaction);

        assertThrows(IllegalStateException.class, () -> transactionalService(transactions).queueDocument(1L, 10L));

        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    private DocumentProcessingQueueService transactionalService(PlatformTransactionManager transactions) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.addAdvice(interceptor);
        return (DocumentProcessingQueueService) proxy.getProxy();
    }

    private TravelDocument document(TravelDocumentProcessingStatus status) {
        TravelDocument document = new TravelDocument();
        document.setId(10L);
        document.setProcessingStatus(status);
        document.setDocumentType(TravelDocumentType.FLIGHT_CONFIRMATION);
        return document;
    }
}
