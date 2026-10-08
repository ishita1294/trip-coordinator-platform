package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.*;
import com.ishita.tripcoordinatorplatform.repository.*;
import com.ishita.tripcoordinatorplatform.request.CompleteDocumentUploadRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentUploadCompletionServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TravelDocumentRepository documents = mock(TravelDocumentRepository.class);
    private final S3Client s3 = mock(S3Client.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DocumentUploadCompletionService service =
            new DocumentUploadCompletionService(trips, documents, s3, transactions, "documents-bucket");
    private final String key = "trips/1/documents/6639360f-16cf-4052-9ea3-0550fdf11e10-flight.pdf";
    private final CompleteDocumentUploadRequest request = new CompleteDocumentUploadRequest(key);
    private final Trip trip = new Trip();

    @BeforeEach
    void setup() {
        trip.setId(1L);
        when(trips.findById(1L)).thenReturn(Optional.of(trip));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(48231L).contentType("application/pdf").build());
        when(documents.saveAndFlush(any())).thenAnswer(invocation -> {
            TravelDocument document = invocation.getArgument(0);document.setId(9L);return document;
        });
    }

    @Test
    void successfulCompletionCreatesOneUploadedDocumentFromObjectMetadata() {
        var response = service.complete(1L, request);
        assertEquals(9L, response.id());
        assertEquals("flight.pdf", response.originalFileName());
        assertEquals("application/pdf", response.contentType());
        assertEquals(TravelDocumentProcessingStatus.UPLOADED, response.processingStatus());
        assertNotNull(response.uploadedAt());
        verify(s3).headObject(HeadObjectRequest.builder().bucket("documents-bucket").key(key).build());
        verify(documents).saveAndFlush(argThat(document -> document.getTrip() == trip
                && document.getStorageKey().equals(key) && document.getDocumentType() == null));
        verify(transactions).commit(any());
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
