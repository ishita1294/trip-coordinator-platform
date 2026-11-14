package com.ishita.tripcoordinatorplatform.ai.document;

/**
 * The content result of one split call: either a validated {@link PartialExtraction} or a short, fixed-text reason why
 * the content was unusable. Provider failures and timeouts are not outcomes; they fail the whole job before any merge.
 */
public record SplitOutcome(SplitCallTarget target, PartialExtraction result, String failureReason) {
    public SplitOutcome {
        if ((result == null) == (failureReason == null))
            throw new IllegalArgumentException("An outcome has either a result or a failure reason");
    }

    public static SplitOutcome succeeded(PartialExtraction result) {
        return new SplitOutcome(result.target(), result, null);
    }

    public static SplitOutcome failed(SplitCallTarget target, String reason) {
        return new SplitOutcome(target, null, reason);
    }

    boolean ok() { return result != null; }
}
