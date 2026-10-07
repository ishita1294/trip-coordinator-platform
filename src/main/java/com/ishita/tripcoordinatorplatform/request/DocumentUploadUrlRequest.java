package com.ishita.tripcoordinatorplatform.request;

public record DocumentUploadUrlRequest(String fileName, String contentType, Long size) {}
