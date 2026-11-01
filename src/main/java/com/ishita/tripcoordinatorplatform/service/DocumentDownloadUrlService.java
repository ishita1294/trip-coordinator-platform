package com.ishita.tripcoordinatorplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import java.time.Duration;

@Service
@ConditionalOnProperty(name="app.document-storage.provider",havingValue="s3")
public class DocumentDownloadUrlService {
    private final S3Presigner presigner;
    private final String bucket;
    public DocumentDownloadUrlService(S3Presigner presigner,@Value("${app.document-storage.s3.bucket}") String bucket) {
        this.presigner=presigner;this.bucket=bucket;
    }
    public record Download(String url,long expiresInSeconds) {}
    public Download issue(String key) {
        var request=GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key)
                        .responseContentDisposition("attachment").build()).build();
        return new Download(presigner.presignGetObject(request).url().toString(),300);
    }
}
