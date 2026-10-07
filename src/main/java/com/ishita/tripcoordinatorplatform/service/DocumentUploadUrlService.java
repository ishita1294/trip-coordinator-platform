package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import com.ishita.tripcoordinatorplatform.request.DocumentUploadUrlRequest;
import com.ishita.tripcoordinatorplatform.response.DocumentUploadUrlResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.document-storage.provider", havingValue = "s3")
public class DocumentUploadUrlService {
    private static final Duration EXPIRATION = Duration.ofMinutes(5);

    private final TripRepository trips;
    private final S3Presigner presigner;
    private final String bucket;

    public DocumentUploadUrlService(TripRepository trips, S3Presigner presigner,
                                   @Value("${app.document-storage.s3.bucket}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("S3 documents bucket is required for presigned uploads");
        }
        this.trips = trips;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public DocumentUploadUrlResponse initiate(Long tripId, DocumentUploadUrlRequest request) {
        if (!trips.existsById(tripId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found");
        }
        if (request == null || request.fileName() == null || request.fileName().isBlank()) {
            throw badRequest("File name is required");
        }
        // Strip both POSIX and Windows path components before creating the server-owned key.
        String name = request.fileName().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest("File name is invalid");
        }
        DocumentUploadPolicy.validate(request.size(), request.contentType());
        String key = "trips/" + tripId + "/documents/" + UUID.randomUUID() + "-" + name;
        var put = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(request.contentType()).build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(EXPIRATION).putObjectRequest(put).build());
        return new DocumentUploadUrlResponse(signed.url().toString(), key, EXPIRATION.toSeconds());
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
