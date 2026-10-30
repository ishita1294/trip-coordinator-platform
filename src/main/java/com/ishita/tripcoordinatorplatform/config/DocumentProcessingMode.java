package com.ishita.tripcoordinatorplatform.config;

/** Selects delivery only; extraction contracts remain on the persisted processing jobs. */
public enum DocumentProcessingMode {
    AWS, LOCAL, DISABLED;

    // An absent mode retains the existing AWS publisher switch for deployed environments.
    public static final String AWS_CONDITION = "'${app.document-processing.execution-mode:}' == 'AWS' || "
            + "('${app.document-processing.execution-mode:}' == '' && ${app.document-processing.publisher.enabled:false})";
    public static final String ENABLED_CONDITION = "'${app.document-processing.execution-mode:}' == 'LOCAL' || (" + AWS_CONDITION + ")";

    public static DocumentProcessingMode resolve(String mode, boolean legacyPublisherEnabled) {
        return mode == null || mode.isEmpty() ? (legacyPublisherEnabled ? AWS : DISABLED) : valueOf(mode);
    }
}
