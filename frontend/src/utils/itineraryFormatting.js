// Itinerary APIs return LocalDateTime: format its calendar fields without a timezone conversion.
export function formatScheduleDate(value, includeWeekday = false) {
  if (!value) return 'Not scheduled';
  const date = new Date(`${value.split('T')[0]}T12:00:00Z`);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    timeZone: 'UTC', year: 'numeric', month: 'short', day: 'numeric',
    ...(includeWeekday ? { weekday: 'short' } : {}),
  }).format(date);
}

export function formatScheduleTime(value) {
  if (!value) return 'Not scheduled';
  const time = value.split('T')[1];
  if (!time) return value;
  const date = new Date(`2000-01-01T${time}Z`);
  if (Number.isNaN(date.getTime())) return time;
  return new Intl.DateTimeFormat(undefined, {
    timeZone: 'UTC', hour: 'numeric', minute: '2-digit',
  }).format(date);
}

export function formatScheduleDateTime(value) {
  return value ? `${formatScheduleDate(value)}, ${formatScheduleTime(value)}` : 'Not scheduled';
}

export const schedulingFlexibility = {
  FLEXIBLE: { label: 'Flexible plan', description: 'Can usually move to another suitable time.' },
  TIME_CONSTRAINED: { label: 'Timing restrictions', description: 'Can move only within known timing restrictions, such as opening hours.' },
  FIXED: { label: 'Fixed time', description: 'Should not be moved unless the reservation or provider allows a change.' },
};
