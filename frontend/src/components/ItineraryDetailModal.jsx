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

function ItineraryDetailModal({ item, conflicts, onClose }) {
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

              return (
                <p key={conflict.id}>
                  {otherTitle || 'Untitled item'} ({formatLabel(conflict.type)})
                </p>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}

export default ItineraryDetailModal;
