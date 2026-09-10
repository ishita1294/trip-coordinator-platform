function formatDateTime(value) {
  if (!value) {
    return 'Not scheduled';
  }

  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

function formatLabel(value) {
  return String(value || 'Unknown').replaceAll('_', ' ');
}

function ItineraryDetailModal({ item,
                                conflicts,
                                resolutionSuggestions,
                                visibleSuggestionConflictId,
                                suggestionLoadingId,
                                applyLoadingId,
                                suggestionErrors,
                                applyErrors,
                                onSuggestResolutions,
                                onApplyResolution,
                                onClose }) {
  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <section
        className="detail-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="detail-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="modal-header">
          <p className="eyebrow">Itinerary Detail</p>
          <button
            type="button"
            className="close-button"
            onClick={onClose}
            aria-label="Close itinerary detail"
          >
            x
          </button>
        </div>

        <h2 id="detail-title">{item.title}</h2>
        <dl className="detail-list">
          <div>
            <dt>Start</dt>
            <dd>{formatDateTime(item.startDateTime)}</dd>
          </div>
          <div>
            <dt>End</dt>
            <dd>{formatDateTime(item.endDateTime)}</dd>
          </div>
          <div>
            <dt>Open Conflict</dt>
            <dd>{conflicts.length > 0 ? 'Yes' : 'No'}</dd>
          </div>
        </dl>

        {conflicts.length > 0 && (
          <div className="modal-conflicts">
            <h3>Conflicts With</h3>
              {conflicts.map((conflict) => {
                  const otherTitle =
                      conflict.firstItemId === item.id
                          ? conflict.secondItemTitle
                          : conflict.firstItemTitle;

                  const suggestions =
                      resolutionSuggestions[conflict.id] || [];

                  return (
                      <div key={conflict.id} className="conflict-detail">
                          <p>
                              {otherTitle || 'Untitled item'} ({formatLabel(conflict.type)})
                          </p>

                          <button
                              type="button"
                              onClick={() => onSuggestResolutions(conflict.id)}
                              disabled={suggestionLoadingId === conflict.id}
                          >
                              {suggestionLoadingId === conflict.id
                                  ? 'Generating suggestions...'
                                  : 'Suggest resolutions'}
                          </button>

                          {visibleSuggestionConflictId === conflict.id
                              && suggestions.length > 0 && (
                              <div className="resolution-suggestions">
                                  <h4>Suggested Resolutions</h4>

                                  {suggestions.map((suggestion, index) => {
                                      const suggestedItemTitle =
                                          suggestion.itemId === conflict.firstItemId
                                              ? conflict.firstItemTitle
                                              : suggestion.itemId === conflict.secondItemId
                                                  ? conflict.secondItemTitle
                                                  : `Item ${suggestion.itemId}`;

                                      return (
                                          <div
                                              key={`${conflict.id}-${suggestion.itemId}-${index}`}
                                              className="resolution-option"
                                          >
                                              <strong>{suggestedItemTitle}</strong>

                                              <p>
                                                  {formatDateTime(suggestion.proposedStartDateTime)}
                                                  {' → '}
                                                  {formatDateTime(suggestion.proposedEndDateTime)}
                                              </p>

                                              <p>{suggestion.reason}</p>

                                              {suggestion.verificationRequired && (
                                                  <p>
                                                      Requires verification
                                                      {suggestion.verificationReason
                                                          ? `: ${suggestion.verificationReason}`
                                                          : ''}
                                                  </p>
                                              )}
                                              <button
                                                  type="button"
                                                  disabled={applyLoadingId === conflict.id}
                                                  onClick={() =>
                                                      onApplyResolution(conflict.id, suggestion)
                                                  }
                                              >
                                                  Apply
                                              </button>
                                          </div>
                                      );
                                  })}
                              </div>
                          )}
                          {suggestionErrors?.[conflict.id] && (
                              <p className="status-message error">
                                  {suggestionErrors[conflict.id]}
                              </p>
                          )}
                          {applyErrors?.[conflict.id] && (
                              <p className="status-message error">
                                  {applyErrors[conflict.id]}
                              </p>
                          )}
                      </div>
                  );
              })}


          </div>
        )}
      </section>
    </div>
  );
}

export default ItineraryDetailModal;
