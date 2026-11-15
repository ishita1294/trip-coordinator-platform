package com.ishita.tripcoordinatorplatform.ai.document;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseStatus;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Collectors;

/**
 * Parses and checks the reply of one split call. Anything wrong with the content is an
 * {@link InvalidDocumentExtractionException}; provider and timeout failures never reach this class.
 */
final class PartialExtractionReader {
    private PartialExtractionReader() {}

    static PartialExtraction read(Response response, CallStats stats, SplitCallTarget target,
            SplitSource source, JsonMapper mapper) {
        if (response.error().isPresent() || response.incompleteDetails().isPresent()
                || !response.status().filter(ResponseStatus.COMPLETED::equals).isPresent())
            throw new InvalidDocumentExtractionException("Semantic extraction response was not completed");
        String text = outputText(response);
        var parsed = TourReadingCodec.readModel(text, mapper);
        var bookings = ActivityEndingReconciler.reconcile(parsed.bookings()).bookings();
        requireShape(target, bookings);
        if (target.kind() == SplitCallTarget.Kind.BOOKING)
            new TourReadingValidator().validateBooking(bookings.getFirst(), target.ordinal(),
                    new ReadingAssertionValidator());
        var facts = SplitSupplementalScope.keep(parsed.supplementalFacts(), target);
        int dropped = parsed.supplementalFacts().size() - facts.size();
        var grounding = source.evidence().validate(
                new TourReading(TourReading.SCHEMA_VERSION, null, bookings, facts, null));
        LoggerFactory.getLogger(PartialExtractionReader.class).info(
                "Document split call target={} ordinal={} elapsedMs={} inputTokens={} cachedInputTokens={} outputTokens={} "
                        + "reasoningTokens={} supplementalFacts={} droppedOutOfScope={} textMatchedQuotations={} "
                        + "unverifiedQuotations={}", target.kind(), target.ordinal(), stats.elapsedMs(), stats.inputTokens(),
                stats.cachedInputTokens(), stats.outputTokens(), stats.reasoningTokens(), facts.size(), dropped,
                grounding.textMatchedQuotations(), grounding.unverifiedQuotations());
        return new PartialExtraction(target, bookings, facts, parsed.supplementalCoverageWarning(), response.id(), stats,
                dropped);
    }

    static String outputText(Response response) {
        return response.output().stream().flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream()).flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text()).collect(Collectors.joining());
    }

    private static void requireShape(SplitCallTarget target, java.util.List<TourReading.Booking> bookings) {
        if (target.kind() == SplitCallTarget.Kind.SHARED) {
            if (!bookings.isEmpty())
                throw new InvalidDocumentExtractionException("Shared extraction returned bookings: " + bookings.size());
            return;
        }
        if (bookings.size() != 1)
            throw new InvalidDocumentExtractionException("Booking " + target.ordinal() + " extraction returned "
                    + bookings.size() + " bookings");
        if (bookings.getFirst().bookingIndex() != target.ordinal())
            throw new InvalidDocumentExtractionException("Booking " + target.ordinal() + " extraction used another index");
    }
}
