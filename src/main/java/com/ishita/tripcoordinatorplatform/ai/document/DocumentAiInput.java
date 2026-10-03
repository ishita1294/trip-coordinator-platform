package com.ishita.tripcoordinatorplatform.ai.document;

public record DocumentAiInput(String originalFileName,
                              String contentType,
                              byte[] content) {
}
