package com.ishita.tripcoordinatorplatform.service;

public record DocumentProcessingMessage(Long documentId, java.util.UUID processingJobId) {}
