package com.ishita.tripcoordinatorplatform.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

public final class DocumentUploadPolicy {
    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    private DocumentUploadPolicy() {}

    public static void validate(Long size, String contentType) {
        if (size == null || size <= 0 || size > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File size must be greater than zero and no more than 20 MiB");
        }
        if (contentType == null || !CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF, JPEG, and PNG documents are supported");
        }
    }
}
