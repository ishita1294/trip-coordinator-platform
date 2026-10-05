package com.ishita.tripcoordinatorplatform.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3DocumentStorageTest {

    private final S3Client client = mock(S3Client.class);
    private final S3DocumentStorage storage = new S3DocumentStorage(client, "document-bucket");

    @Test
    void storesBytesWithConfiguredBucketMetadataAndReturnsOnlyObjectKey() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    PutObjectRequest request = invocation.getArgument(0);
                    RequestBody body = invocation.getArgument(1);
                    assertEquals("document-bucket", request.bucket());
                    assertEquals("application/pdf", request.contentType());
                    assertEquals(bytes.length, body.optionalContentLength().orElseThrow());
                    try (var stream = body.contentStreamProvider().newStream()) {
                        assertArrayEquals(bytes, stream.readAllBytes());
                    }
                    return null;
                });

        String key = storage.store(12L, "ticket.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));

        assertTrue(key.startsWith("trips/12/documents/"));
        assertTrue(key.endsWith("-ticket.pdf"));
        UUID.fromString(key.substring("trips/12/documents/".length(), key.length() - "-ticket.pdf".length()));
        verify(client).putObject(argThat((PutObjectRequest request) -> key.equals(request.key())), any(RequestBody.class));
    }

    @Test
    void removesUnixAndWindowsDirectoryComponents() {
        for (String filename : new String[]{"../../private/ticket.pdf", "C:\\private\\ticket.pdf", "../private\\ticket.pdf"}) {
            String key = storage.store(1L, filename, "application/pdf", 0, new ByteArrayInputStream(new byte[0]));
            assertTrue(key.matches("trips/1/documents/[0-9a-f-]{36}-ticket\\.pdf"));
        }
    }

    @Test
    void rejectsMissingFilenamePortion() {
        for (String filename : new String[]{"directory/", ".", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> storage.store(
                    1L, filename, "application/pdf", 0, new ByteArrayInputStream(new byte[0])));
        }
        verifyNoInteractions(client);
    }

    @Test
    void opensConfiguredBucketAndKeyAndReturnsObjectStream() throws Exception {
        String key = "trips/1/documents/ticket.pdf";
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
                GetObjectResponse.builder().build(), new ByteArrayInputStream(new byte[]{5, 6}));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        try (var stream = storage.open(key)) {
            assertSame(response, stream);
            assertArrayEquals(new byte[]{5, 6}, stream.readAllBytes());
        }
        verify(client).getObject(argThat((GetObjectRequest request) ->
                "document-bucket".equals(request.bucket()) && key.equals(request.key())));
    }

    @Test
    void wrapsUploadSdkFailureWithStorageError() {
        SdkClientException failure = SdkClientException.create("Connection failed");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> storage.store(
                1L, "ticket.pdf", "application/pdf", 0, new ByteArrayInputStream(new byte[0])));

        assertEquals("Failed to store document in S3", error.getMessage());
        assertSame(failure, error.getCause());
    }

    @Test
    void wrapsOpenS3FailureWithStorageError() {
        S3Exception.Builder builder = S3Exception.builder();
        builder.message("Access denied");
        builder.statusCode(403);
        var failure = builder.build();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(failure);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> storage.open("trips/1/documents/ticket.pdf"));

        assertEquals("Failed to open stored document from S3", error.getMessage());
        assertSame(failure, error.getCause());
    }

    @Test
    void requiresBucketConfiguration() {
        for (String bucket : new String[]{null, "", " "}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new S3DocumentStorage(client, bucket));
            assertEquals("app.document-storage.s3.bucket is required when using S3 storage", error.getMessage());
        }
    }
}
