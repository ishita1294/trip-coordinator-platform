import { useEffect, useRef, useState } from 'react';

function formatTimestamp(value) {
  return value ? new Date(value).toLocaleString() : 'Unknown date';
}

async function readResponse(response, fallback) {
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    if (response.ok) throw new Error('The server returned an invalid JSON response.');
    throw new Error(text || fallback);
  }
  if (!response.ok) {
    throw new Error(data?.detail || data?.message || data?.error ||
      (typeof data === 'string' ? data : fallback));
  }
  return data;
}

const segmentFields = [
  ['segmentOrder', 'Segment order'],
  ['flightNumber', 'Flight number'],
  ['departureAirportCode', 'Departure airport'],
  ['departureDate', 'Departure date'],
  ['departureTime', 'Departure time'],
  ['arrivalAirportCode', 'Arrival airport'],
  ['arrivalDate', 'Arrival date'],
  ['arrivalTime', 'Arrival time'],
];

function isPresent(value) {
  return value != null && (typeof value !== 'string' || value.trim() !== '');
}

function isComplete(proposal) {
  return proposal.length > 0 && proposal.every((group) =>
    isPresent(group.confirmationNumber) && isPresent(group.confirmationNumberType)
    && group.segments.length > 0 && group.segments.every((segment) =>
      segmentFields.every(([key]) => isPresent(segment[key]))));
}

function isHistorical(proposal) {
  const now = new Date();
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  return proposal.some((group) => {
    const latestArrivalDate = group.segments.reduce((latest, segment) =>
      segment.arrivalDate != null && (latest == null || segment.arrivalDate > latest)
        ? segment.arrivalDate : latest, null);
    // ISO calendar dates compare directly; arrivals today are not historical.
    return latestArrivalDate != null && latestArrivalDate < today;
  });
}

function displayValue(key, value) {
  if (!isPresent(value)) return 'Not extracted';
  if (key.endsWith('Date') && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return new Date(`${value}T00:00:00`).toLocaleDateString(undefined,
      { year: 'numeric', month: 'short', day: 'numeric' });
  }
  if (key.endsWith('Time') && /^\d{2}:\d{2}(:\d{2})?$/.test(value)) {
    return new Date(`2000-01-01T${value}`).toLocaleTimeString(undefined,
      { hour: 'numeric', minute: '2-digit' });
  }
  return value;
}

function TravelDocumentsTab({ tripId }) {
  const baseUrl = `/api/trips/${tripId}/documents`;
  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [file, setFile] = useState(null);
  const fileInput = useRef(null);
  const reviewDialog = useRef(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState(null);
  const [reviewState, setReviewState] = useState('IDLE');
  const [reviewError, setReviewError] = useState('');
  const [proposal, setProposal] = useState([]);
  const [busy, setBusy] = useState({});
  const pendingActions = useRef(new Set());
  const reviewVersion = useRef(0);
  const actionVersion = useRef(0);
  const [actionErrors, setActionErrors] = useState({});
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    fetch(baseUrl, { signal: controller.signal })
      .then((response) => readResponse(response, 'Unable to load documents.'))
      .then((data) => setDocuments(Array.isArray(data) ? data : []))
      .catch((requestError) => {
        if (!controller.signal.aborted) setError(requestError.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      reviewVersion.current += 1;
    };
  }, [baseUrl]);

  const reviewOpen = reviewState !== 'IDLE';
  useEffect(() => {
    if (!reviewOpen) return;
    reviewDialog.current.showModal();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previousOverflow; };
  }, [reviewOpen]);

  async function refreshDocuments() {
    const data = await readResponse(await fetch(baseUrl), 'Unable to refresh documents.');
    setDocuments(Array.isArray(data) ? data : []);
  }

  function clearMessages() {
    actionVersion.current += 1;
    setActionErrors({});
    setError('');
    setMessage('');
    setReviewError('');
    setReviewState((current) => current === 'ERROR' ? 'IDLE' : current);
  }

  function closeReview() {
    reviewVersion.current += 1;
    setReviewState('IDLE');
    setSelectedDocumentId(null);
    setReviewError('');
    setActionErrors((current) => ({ ...current, confirm: '' }));
    setProposal([]);
  }

  async function loadCurrentExtraction(documentId) {
    clearMessages();
    closeReview();
    setSelectedDocumentId(documentId);
    const version = reviewVersion.current;
    setReviewState('LOADING');
    try {
      const data = await readResponse(
        await fetch(`${baseUrl}/${documentId}/extraction`), 'Unable to load document for review.');
      if (version !== reviewVersion.current) return;
      if (data?.documentId !== documentId) {
        throw new Error('The server returned a different document than requested.');
      }
      if (data.documentType !== 'FLIGHT_CONFIRMATION') {
        throw new Error('Only flight documents can be reviewed here.');
      }
      const groups = data?.extractedData?.reservations;
      if (!Array.isArray(groups)) {
        throw new Error('Unable to review this document. Reprocess it and try again.');
      }
      // Keep incomplete proposals visible, including missing groups or segments.
      setProposal(groups.map((group) => ({
        ...group,
        segments: Array.isArray(group?.segments)
          ? group.segments.map((segment) => segment ?? {}) : [],
      })));
      setReviewState('READY');
    } catch (requestError) {
      if (version !== reviewVersion.current) return;
      setReviewError(requestError.message || 'Unable to load document for review.');
      setReviewState('ERROR');
    }
  }

  async function runAction(action, callback) {
    if (pendingActions.current.has(action)) return;
    pendingActions.current.add(action);
    setBusy((current) => ({ ...current, [action]: true }));
    clearMessages();
    const version = actionVersion.current;
    try {
      await callback();
    } catch (requestError) {
      if (version !== actionVersion.current) return;
      setActionErrors((current) => ({
        ...current, [action]: requestError.message || 'Unable to complete this action.',
      }));
    } finally {
      pendingActions.current.delete(action);
      setBusy((current) => ({ ...current, [action]: false }));
    }
  }

  function upload(event) {
    event.preventDefault();
    if (!file) return;
    runAction('upload', async () => {
      const body = new FormData();
      body.append('file', file);
      await readResponse(await fetch(baseUrl, { method: 'POST', body }), 'Unable to upload document.');
      setFile(null);
      if (fileInput.current) fileInput.current.value = '';
      await refreshDocuments();
      setMessage('Document uploaded.');
    });
  }

  function processDocument(documentId) {
    closeReview();
    setSelectedDocumentId(documentId);
    runAction(`process-${documentId}`, async () => {
      let data;
      try {
        data = await readResponse(
          await fetch(`${baseUrl}/${documentId}/process`, { method: 'POST' }),
          'Unable to process document.');
        if (data?.documentId !== documentId) {
          throw new Error('The server returned a processing result for a different document.');
        }
      } catch (requestError) {
        // Refresh FAILED status if available without hiding the original processing error.
        try { await refreshDocuments(); } catch { /* The processing error remains the relevant failure. */ }
        throw requestError;
      }
      setDocuments((current) => current.map((document) => document.id === documentId
        ? { ...document, processingStatus: data.processingStatus, documentType: data.documentType }
        : document));
      await refreshDocuments();
      if (data.processingStatus === 'FAILED') {
        throw new Error('Document processing failed. Retry processing the document.');
      }
      // Review stays closed; the user explicitly opens the new current proposal.
    });
  }

  function confirm(event) {
    event.preventDefault();
    if (selectedDocumentId == null || reviewState !== 'READY' || !isComplete(proposal)) {
      setActionErrors((current) => ({ ...current, confirm: 'Open a document for review before confirming.' }));
      return;
    }
    if (isHistorical(proposal)) return;
    const documentId = selectedDocumentId;
    runAction('confirm', async () => {
      await readResponse(await fetch(`${baseUrl}/${documentId}/confirm`, {
        method: 'POST',
      }), 'Unable to confirm flight reservation.');
      setMessage('Flight reservation saved');
      setDocuments((current) => current.map((document) => document.id === documentId
        ? { ...document, processingStatus: 'PROCESSED' } : document));
      closeReview();
      try {
        await refreshDocuments();
      } catch (refreshError) {
        setError(`Flight reservation saved, but the document list could not be refreshed: ${refreshError.message}`);
      }
    });
  }

  const historical = isHistorical(proposal);
  const selectedDocument = documents.find((document) => document.id === selectedDocumentId);
  const statusLabels = { UPLOADED: 'Uploaded', PROCESSING: 'Processing...',
    REVIEW_REQUIRED: 'Review required', FAILED: 'Processing failed', PROCESSED: 'Processed' };

  return (
    <section className="travel-documents" aria-label="Travel Documents">
      <form className="document-upload" onSubmit={upload}>
        <label htmlFor="travel-document-file">Upload PDF, JPEG, or PNG</label>
        <input id="travel-document-file" ref={fileInput} type="file"
               accept="application/pdf,image/jpeg,image/png" disabled={!!busy.upload || !!busy.confirm}
               onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
        <button type="submit" disabled={!file || !!busy.upload || !!busy.confirm}>
          {busy.upload ? 'Uploading...' : 'Upload'}
        </button>
      </form>

      {message && <p className="document-success" role="status">{message}</p>}
      {error && <p className="status-message error" role="alert">{error}</p>}
      {actionErrors.upload && <p className="status-message error" role="alert">{actionErrors.upload}</p>}
      {loading && <p className="status-message">Loading documents...</p>}

      <div className="document-list">
        {!loading && documents.length === 0 && <p className="empty-message">No documents uploaded.</p>}
        {documents.map((document) => {
          const processing = !!busy[`process-${document.id}`] || document.processingStatus === 'PROCESSING';
          return (
            <article className="document-card" key={document.id}>
              <h2>{document.originalFileName}</h2>
              <p className="document-status">
                {processing ? 'Processing...' : statusLabels[document.processingStatus] || 'Unknown status'}
              </p>
              <p className="document-status">Uploaded {formatTimestamp(document.uploadedAt)}</p>
              <div className="document-actions">
                {!processing && document.processingStatus === 'REVIEW_REQUIRED' && (
                  <button type="button" disabled={!!busy.confirm
                    || (selectedDocumentId === document.id && reviewState === 'LOADING')}
                          onClick={() => loadCurrentExtraction(document.id)}>Review extracted details</button>
                )}
                {!processing && ['UPLOADED', 'FAILED', 'REVIEW_REQUIRED'].includes(document.processingStatus) && (
                  <button type="button" disabled={!!busy.confirm}
                          onClick={() => processDocument(document.id)}>
                    {document.processingStatus === 'REVIEW_REQUIRED' ? 'Reprocess'
                      : document.processingStatus === 'FAILED' ? 'Retry' : 'Process'}
                  </button>
                )}
              </div>
              {actionErrors[`process-${document.id}`] && <p className="status-message error" role="alert">
                {actionErrors[`process-${document.id}`]}
              </p>}
            </article>
          );
        })}
      </div>

      {reviewOpen && (
        <dialog ref={reviewDialog} className="document-review-modal"
                aria-labelledby="document-review-title"
                onCancel={(event) => {
                  event.preventDefault();
                  if (!busy.confirm) closeReview();
                }}
                onClick={(event) => {
                  if (event.target !== event.currentTarget || busy.confirm) return;
                  const bounds = event.currentTarget.getBoundingClientRect();
                  if (event.clientX < bounds.left || event.clientX > bounds.right
                    || event.clientY < bounds.top || event.clientY > bounds.bottom) closeReview();
                }}>
          <header className="document-review-header">
            <div>
              <h2 id="document-review-title">Review extracted details</h2>
              <p>{selectedDocument?.originalFileName}</p>
            </div>
            <button type="button" aria-label="Close review" disabled={!!busy.confirm}
                    onClick={closeReview}>×</button>
          </header>
          <div className="document-review-body">
            {reviewState === 'LOADING' && <p className="status-message" role="status">Loading...</p>}
            {reviewState === 'ERROR' && reviewError && <p className="status-message error" role="alert">{reviewError}</p>}
            {reviewState === 'READY' && selectedDocument?.processingStatus === 'REVIEW_REQUIRED' && (
              <form className="flight-review" onSubmit={confirm}>
                <p>Review the AI extraction before saving it exactly as shown.
                  If details are missing or wrong, reprocess the document.</p>
                {!isComplete(proposal) && <p className="status-message error" role="alert">
                  Some required flight details could not be extracted from this document.
                </p>}
                {historical && <p className="status-message error" role="alert">
                  This flight reservation is historical and cannot be saved.
                </p>}
                {proposal.map((group, groupIndex) => (
                  <fieldset className="reservation-block" key={groupIndex}>
                    <legend>Reservation {groupIndex + 1}</legend>
                    <div className="document-form-grid">
                      <div>Confirmation number<br /><span>{displayValue('confirmationNumber', group.confirmationNumber)}</span></div>
                      <div>Type<br /><span>{displayValue('confirmationNumberType', group.confirmationNumberType)}</span></div>
                    </div>
                    {group.segments.length === 0 && <p>Segments: Not extracted</p>}
                    {group.segments.map((segment, segmentIndex) => (
                      <div className="segment-block" key={segmentIndex}>
                        <h3>Segment {segmentIndex + 1}</h3>
                        <div className="document-form-grid">
                          {segmentFields.map(([key, label]) => (
                            <div key={key}>{label}<br /><span>{displayValue(key, segment[key])}</span></div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </fieldset>
                ))}
                {isComplete(proposal) && <button type="submit" disabled={!!busy.confirm || historical}>
                  {busy.confirm ? 'Confirming...' : 'Confirm & Save'}
                </button>}
                <button type="button" disabled={!!busy.confirm || !!busy[`process-${selectedDocumentId}`]}
                        onClick={() => processDocument(selectedDocumentId)}>Reprocess</button>
                {actionErrors.confirm && <p className="status-message error" role="alert">{actionErrors.confirm}</p>}
              </form>
            )}
          </div>
        </dialog>
      )}
    </section>
  );
}

export default TravelDocumentsTab;
