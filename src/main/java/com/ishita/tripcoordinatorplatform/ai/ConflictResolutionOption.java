package com.ishita.tripcoordinatorplatform.ai;
import java.time.LocalDateTime;

/**
 * Represents one schedule change proposed by the AI.
 * The backend must validate the option before it can be shown or applied.
 */
public class ConflictResolutionOption {

    private Long itemId;

    public LocalDateTime getProposedStartDateTime() {
        return proposedStartDateTime;
    }

    public void setProposedStartDateTime(LocalDateTime proposedStartDateTime) {
        this.proposedStartDateTime = proposedStartDateTime;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public LocalDateTime getProposedEndDateTime() {
        return proposedEndDateTime;
    }

    public void setProposedEndDateTime(LocalDateTime proposedEndDateTime) {
        this.proposedEndDateTime = proposedEndDateTime;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    private LocalDateTime proposedStartDateTime;
    private LocalDateTime proposedEndDateTime;
    private String reason;

    public ConflictResolutionOption() {
    }

    private boolean verificationRequired;
    private String verificationReason;

    public boolean isVerificationRequired() {
        return verificationRequired;
    }

    public void setVerificationRequired(boolean verificationRequired) {
        this.verificationRequired = verificationRequired;
    }

    public String getVerificationReason() {
        return verificationReason;
    }

    public void setVerificationReason(String verificationReason) {
        this.verificationReason = verificationReason;
    }
}
