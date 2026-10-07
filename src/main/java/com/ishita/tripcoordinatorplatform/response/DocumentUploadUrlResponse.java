package com.ishita.tripcoordinatorplatform.response;

public record DocumentUploadUrlResponse(String uploadUrl, String storageKey, long expiresInSeconds) {}
