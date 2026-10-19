import { formatScheduleDate, formatScheduleTime, schedulingFlexibility } from '../utils/itineraryFormatting.js';

function ItineraryCard({ item, hasOpenConflict, isSelected, isHighlighted, onClick }) {
  const timing = schedulingFlexibility[item.flexibility];
  const spansDates = item.endDateTime && item.startDateTime?.split('T')[0] !== item.endDateTime.split('T')[0];
  const location = item.reservation?.structuredLocation?.name || item.reservation?.location
    || item.activity?.destination?.name;

  return (
    <button
      type="button"
      className={`itinerary-item ${isSelected ? 'is-selected' : ''} ${isHighlighted ? 'is-overlap-highlight' : ''}`}
      onClick={onClick}
    >
      <span className="item-icon" aria-hidden="true">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
          <circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" />
        </svg>
      </span>
      <span className="time-block">
        <span>{formatScheduleTime(item.startDateTime)}</span>
        <span>{item.endDateTime
          ? `– ${spansDates ? `${formatScheduleDate(item.endDateTime)} · ` : ''}${formatScheduleTime(item.endDateTime)}`
          : 'End not planned'}</span>
      </span>
      <span className="item-details">
        <span className="item-title">{item.title}</span>
        {location && <span className="item-location">{location}</span>}
      </span>
      <span className="item-tags">
        {timing && <span className={`timing-badge timing-${item.flexibility.toLowerCase()}`}
          title={`${timing.description}${!item.endDateTime ? ' Only a start is scheduled; the end is not planned.' : ''}`}>{timing.label}</span>}
        {hasOpenConflict && <span className="conflict-badge">Open conflict</span>}
      </span>
      <span className="item-chevron" aria-hidden="true">›</span>
    </button>
  );
}

export default ItineraryCard;
