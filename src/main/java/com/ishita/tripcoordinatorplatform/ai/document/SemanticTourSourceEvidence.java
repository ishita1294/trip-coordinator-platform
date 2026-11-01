package com.ishita.tripcoordinatorplatform.ai.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Checks available source text without treating scanned or image evidence as verified. */
final class SemanticTourSourceEvidence {
    enum QuotationCheck { TEXT_MATCHED, UNVERIFIED }
    record Grounding(int textMatchedQuotations, int unverifiedQuotations) {}
    private record Page(String text, boolean completeText) {}
    private final List<Page> pages;

    private SemanticTourSourceEvidence(List<Page> pages) {
        this.pages = List.copyOf(pages);
    }

    /** Source text is an optional checking capability, not a new extraction input requirement. */
    static SemanticTourSourceEvidence read(DocumentAiInput source) {
        if ("image/jpeg".equals(source.contentType()) || "image/png".equals(source.contentType()))
            return new SemanticTourSourceEvidence(List.of(new Page("", false)));
        if (!"application/pdf".equals(source.contentType()))
            throw new InvalidDocumentExtractionException("Only PDF, JPEG, and PNG documents are supported");
        try (var pdf = Loader.loadPDF(source.content())) {
            var text = new PDFTextStripper();
            text.setSortByPosition(true);
            var pages = new ArrayList<Page>();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                text.setStartPage(page);
                text.setEndPage(page);
                String extracted = "";
                try {
                    extracted = normalize(text.getText(pdf));
                    // A text header does not make a scanned body readable. Raster content (including
                    // nested forms) may contain additional wording that PDFTextStripper cannot see.
                    boolean complete = !extracted.isEmpty() && extracted.indexOf('\uFFFD') < 0
                            && !hasInlineImages(pdf.getPage(page - 1))
                            && !hasImages(pdf.getPage(page - 1).getResources(),
                                    Collections.newSetFromMap(new IdentityHashMap<>()));
                    pages.add(new Page(extracted, complete));
                } catch (IOException unreadablePage) {
                    // Keep checks on other readable pages even when this page cannot be inspected.
                    pages.add(new Page(extracted, false));
                }
            }
            return new SemanticTourSourceEvidence(pages);
        } catch (IOException exception) {
            // A local parser limitation must not disable the provider's existing PDF support.
            // No source text, passwords or parser details are logged or copied into the extraction.
            org.slf4j.LoggerFactory.getLogger(SemanticTourSourceEvidence.class)
                    .info("Document source text unavailable reason=PDF_TEXT_READ_FAILED; quotations remain unverified");
            return new SemanticTourSourceEvidence(List.of());
        }
    }

    private static boolean hasInlineImages(org.apache.pdfbox.contentstream.PDContentStream content) throws IOException {
        return new org.apache.pdfbox.pdfparser.PDFStreamParser(content).parse().stream()
                .anyMatch(token -> token instanceof org.apache.pdfbox.contentstream.operator.Operator operator
                        && "BI".equals(operator.getName()));
    }

    private static boolean hasImages(PDResources resources, Set<org.apache.pdfbox.cos.COSDictionary> visited)
            throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) return false;
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject) return true;
            if (object instanceof PDFormXObject form
                    && (hasInlineImages(form) || hasImages(form.getResources(), visited))) return true;
        }
        return false;
    }

    /** Structural validation runs first. Counts are diagnostic only, never persisted as evidence truth. */
    Grounding validate(SemanticTourExtractionV3 extraction) {
        var validator = new SemanticTourExtractionV3Validator();
        var evidence = new ArrayList<SemanticFactV3.SourceEvidence>();
        for (var booking : extraction.bookings()) {
            validator.fields(booking).values().forEach(fact -> evidence.addAll(fact.evidence()));
            evidence.addAll(booking.lifecycle().evidence());
            booking.replaces().forEach(fact -> evidence.addAll(fact.evidence()));
            booking.temporalConstraints().forEach(constraint -> evidence.addAll(constraint.fact().evidence()));
        }
        extraction.supplementalFacts().forEach(supplemental -> evidence.addAll(supplemental.fact().evidence()));
        int matched = 0;
        for (var reference : evidence)
            if (validateQuotation(reference) == QuotationCheck.TEXT_MATCHED) matched++;
        return new Grounding(matched, evidence.size() - matched);
    }

    QuotationCheck validateQuotation(SemanticFactV3.SourceEvidence evidence) {
        if (evidence == null || evidence.quotation() == null || normalize(evidence.quotation()).isEmpty())
            throw new InvalidDocumentExtractionException("Source quotation is missing");
        String quotation = normalize(evidence.quotation());
        Integer page = evidence.reportedPage();
        if (page != null) {
            if (page <= 0 || (!pages.isEmpty() && page > pages.size()))
                throw new InvalidDocumentExtractionException("Source quotation page is outside the document");
            if (pages.isEmpty()) return QuotationCheck.UNVERIFIED;
            var source = pages.get(page - 1);
            if (source.text().contains(quotation)) return QuotationCheck.TEXT_MATCHED;
            if (!source.completeText()) return QuotationCheck.UNVERIFIED;
        } else {
            // A missing page number must not join pages or silently choose an unreadable page.
            // If any page is unreadable, an unmatched quotation has unknown origin, not a text match.
            if (pages.stream().anyMatch(source -> source.text().contains(quotation)))
                return QuotationCheck.TEXT_MATCHED;
            if (pages.isEmpty() || pages.stream().anyMatch(source -> !source.completeText()))
                return QuotationCheck.UNVERIFIED;
        }
        throw new InvalidDocumentExtractionException("Source quotation is not a contiguous excerpt on its reported page");
    }

    // Only canonical Unicode composition and whitespace are normalized. Keep punctuation, case,
    // numbers, hyphens, words and their order; never remove an omitted passage or join pages.
    private static String normalize(String text) {
        var result = new StringBuilder();
        boolean space = false;
        for (int point : Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().toArray()) {
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = !result.isEmpty();
            } else {
                if (space) result.append(' ');
                result.appendCodePoint(point);
                space = false;
            }
        }
        return result.toString();
    }
}
