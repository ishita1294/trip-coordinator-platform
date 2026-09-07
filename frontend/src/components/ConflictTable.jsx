function formatLabel(value) {
  return String(value || 'Unknown').replaceAll('_', ' ');
}

function ConflictTable({ conflicts, selectedConflictId, onConflictSelect }) {
  if (conflicts.length === 0) {
    return <p className="empty-message">No open conflicts found.</p>;
  }

  return (
    <div className="conflicts-table">
      <div className="conflict-row table-head">
        <span>Member</span>
        <span>Type</span>
        <span>First Item</span>
        <span>Second Item</span>
        <span>Status</span>
      </div>
      {conflicts.map((conflict) => (
        <button
          type="button"
          className={`conflict-row conflict-action ${
            selectedConflictId === conflict.id ? 'is-selected' : ''
          }`}
          key={conflict.id}
          onClick={() => onConflictSelect(conflict)}
        >
          <span>{conflict.memberName || 'Unknown member'}</span>
          <span>{formatLabel(conflict.type)}</span>
          <span>{conflict.firstItemTitle || 'Untitled item'}</span>
          <span>{conflict.secondItemTitle || 'Untitled item'}</span>
          <span>{formatLabel(conflict.status)}</span>
        </button>
      ))}
    </div>
  );
}

export default ConflictTable;
