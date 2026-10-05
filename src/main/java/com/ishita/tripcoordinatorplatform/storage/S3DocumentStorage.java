package com.ishita.tripcoordinatorplatform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.document-storage.provider", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3Client;
    private final String bucket;

    public S3DocumentStorage(
            S3Client s3Client,
            @Value("${app.document-storage.s3.bucket:}") String bucket
    ) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.document-storage.s3.bucket is required when using S3 storage");
        }
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String store(Long tripId, String originalFileName, String contentType,
                        long contentLength, InputStream inputStream) {
        // Strip both Unix and Windows directory components from client filenames.
        String filename = originalFileName.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank() || filename.equals(".") || filename.equals("..")) {
            throw new IllegalArgumentException("Document filename is required");
        }
        String storageKey = "trips/" + tripId + "/documents/" + UUID.randomUUID() + "-" + filename;

        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(storageKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(inputStream, contentLength));
            return storageKey;
        } catch (SdkException exception) {
            throw new IllegalStateException("Failed to store document in S3", exception);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            // The caller closes the returned response stream after reading.
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
        } catch (SdkException exception) {
            throw new IllegalStateException("Failed to open stored document from S3", exception);
        }
    }
}
