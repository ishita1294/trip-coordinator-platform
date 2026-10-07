package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.repository.TripRepository;
import com.ishita.tripcoordinatorplatform.request.CompleteDocumentUploadRequest;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentSummaryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "app.document-storage.provider", havingValue = "s3")
public class DocumentUploadCompletionService {
    private static final Pattern FILE_KEY = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}-(.+)$");
    private final TripRepository trips;
    private final TravelDocumentRepository documents;
    private final S3Client s3;
    private final String bucket;
    private final TransactionTemplate registration;

    public DocumentUploadCompletionService(TripRepository trips, TravelDocumentRepository documents,
                                          S3Client s3, PlatformTransactionManager transactions,
                                          @Value("${app.document-storage.s3.bucket}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("S3 documents bucket is required for upload completion");
        }
        this.trips = trips;
        this.documents = documents;
        this.s3 = s3;
        this.bucket = bucket;
        this.registration = new TransactionTemplate(transactions);
        this.registration.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TravelDocumentSummaryResponse complete(Long tripId, CompleteDocumentUploadRequest request) {
        var trip = trips.findById(tripId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
        String key = request == null ? null : request.storageKey();
        String filename = filename(tripId, key);
        var existing = documents.findByStorageKey(key);
        if (existing.isPresent()) return response(tripId, existing.get());

        HeadObjectResponse object;
        try {
            object = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uploaded document object not found");
            }
            throw failure; // Permission and operational failures must not masquerade as missing uploads.
        }
        try {
            DocumentUploadPolicy.validate(object.contentLength(), object.contentType());
        } catch (ResponseStatusException invalidObject) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            throw invalidObject;
        }

        // S3 calls finish before the short database registration transaction starts.
        try {
            return registration.execute(status -> {
                var alreadyRegistered = documents.findByStorageKey(key);
                if (alreadyRegistered.isPresent()) return response(tripId, alreadyRegistered.get());
                var document = new TravelDocument();
                document.setTrip(trip);
                document.setOriginalFileName(filename);
                document.setStorageKey(key);
                document.setContentType(object.contentType());
                document.setProcessingStatus(TravelDocumentProcessingStatus.UPLOADED);
                document.setUploadedAt(Instant.now());
                return response(tripId, documents.saveAndFlush(document));
            });
        } catch (DataIntegrityViolationException failure) {
            // A concurrent completion may have won the unique-key insert. Read only after rollback.
            return documents.findByStorageKey(key).map(document -> response(tripId, document))
                    .orElseThrow(() -> failure);
        }
    }

    private String filename(Long tripId, String key) {
        String prefix = "trips/" + tripId + "/documents/";
        if (key == null || !key.startsWith(prefix)) throw invalidKey();
        var match = FILE_KEY.matcher(key.substring(prefix.length()));
        if (!match.matches()) throw invalidKey();
        String name = match.group(1);
        if (name.isBlank() || !name.equals(name.trim()) || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidKey();
        }
        return name;
    }

    private TravelDocumentSummaryResponse response(Long tripId, TravelDocument document) {
        if (!tripId.equals(document.getTrip().getId())) throw invalidKey();
        return TravelDocumentSummaryResponse.from(document);
    }

    private ResponseStatusException invalidKey() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document storage key for this trip");
    }
}
