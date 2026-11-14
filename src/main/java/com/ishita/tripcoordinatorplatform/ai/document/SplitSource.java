package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.Base64;

/**
 * One document prepared once for all calls of a split extraction: a private copy of the bytes, their digest, the
 * base64 data URL and the optional PDF text used to check quotations. Calls only read it, so threads can share it.
 */
public final class SplitSource {
    private final DocumentAiInput input;
    private final String sha256;
    private final String dataUrl;
    private final TourSourceEvidenceChecker evidence;

    private SplitSource(DocumentAiInput input, TourSourceEvidenceChecker evidence) {
        this.input = input;
        this.sha256 = TourExtractionCodec.sourceSha256(input.content());
        this.dataUrl = "data:" + input.contentType() + ";base64," + Base64.getEncoder().encodeToString(input.content());
        this.evidence = evidence;
    }

    static SplitSource of(DocumentAiInput document) {
        if (document == null || document.content() == null || document.content().length == 0)
            throw new IllegalArgumentException("Document content is required for semantic extraction");
        var copy = new DocumentAiInput(document.originalFileName(), document.contentType(), document.content().clone());
        return new SplitSource(copy, TourSourceEvidenceChecker.read(copy));
    }

    public String sha256() { return sha256; }
    DocumentAiInput input() { return input; }
    String dataUrl() { return dataUrl; }
    TourSourceEvidenceChecker evidence() { return evidence; }
}
