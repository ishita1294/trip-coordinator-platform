export function formatTimestamp(value) {
  return value ? new Date(value).toLocaleString() : 'Unknown date';
}

export const segmentFields = [
  ['segmentOrder', 'Segment order'],
  ['flightNumber', 'Flight number'],
  ['departureAirportCode', 'Departure airport'],
  ['departureDate', 'Departure date'],
  ['departureTime', 'Departure time'],
  ['arrivalAirportCode', 'Arrival airport'],
  ['arrivalDate', 'Arrival date'],
  ['arrivalTime', 'Arrival time'],
];

export function isPresent(value) {
  return value != null && (typeof value !== 'string' || value.trim() !== '');
}

export function isComplete(proposal) {
  return proposal.length > 0 && proposal.every((group) =>
    isPresent(group.confirmationNumber) && isPresent(group.confirmationNumberType)
    && group.segments.length > 0 && group.segments.every((segment) =>
      segmentFields.every(([key]) => isPresent(segment[key]))));
}

export function isHistorical(proposal) {
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

export function displayValue(key, value) {
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
