package com.ishita.tripcoordinatorplatform.ai.document;

/**
 * Receives the raw reply text of each split call for local debugging. The default records nothing; the only
 * implementation writes files and exists only when explicitly configured on a local machine.
 */
public interface SplitReplyRecorder {
    SplitReplyRecorder NONE = (target, responseId, status, rawReply) -> { };

    /** Must not throw into extraction; callers also guard against it. The reply may contain document text. */
    void record(SplitCallTarget target, String responseId, String status, String rawReply);
}
