const travelImages = [
  {
    keywords: ['breakfast', 'brunch', 'coffee'],
    url: 'https://images.unsplash.com/photo-1493770348161-369560ae357d?auto=format&fit=crop&w=420&q=80',
    alt: 'Breakfast table with coffee and pastries',
  },
  {
    keywords: ['museum', 'gallery', 'exhibit'],
    url: 'https://images.unsplash.com/photo-1564399579883-451a5d44ec08?auto=format&fit=crop&w=420&q=80',
    alt: 'Museum gallery with framed artwork',
  },
  {
    keywords: ['flight', 'airport', 'plane'],
    url: 'https://images.unsplash.com/photo-1436491865332-7a61a109cc05?auto=format&fit=crop&w=420&q=80',
    alt: 'Airplane wing above clouds',
  },
  {
    keywords: ['hotel', 'check in', 'stay'],
    url: 'https://images.unsplash.com/photo-1566073771259-6a8506099945?auto=format&fit=crop&w=420&q=80',
    alt: 'Hotel pool and resort building',
  },
  {
    keywords: ['dinner', 'lunch', 'restaurant'],
    url: 'https://images.unsplash.com/photo-1414235077428-338989a2e8c0?auto=format&fit=crop&w=420&q=80',
    alt: 'Restaurant table set for a meal',
  },
  {
    keywords: ['walk', 'tour', 'sightseeing'],
    url: 'https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=420&q=80',
    alt: 'Traveler looking across a scenic landscape',
  },
];

const fallbackImage = {
  url: 'https://images.unsplash.com/photo-1469854523086-cc02fe5d8800?auto=format&fit=crop&w=420&q=80',
  alt: 'Open road through a travel landscape',
};

function formatDateTime(value) {
  if (!value) {
    return 'Not scheduled';
  }

  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

function getTravelImage(title) {
  const normalizedTitle = String(title || '').toLowerCase();
  return (
    travelImages.find((image) =>
      image.keywords.some((keyword) => normalizedTitle.includes(keyword)),
    ) || fallbackImage
  );
}

function ItineraryCard({
  item,
  hasOpenConflict,
  isSelected,
  isHighlighted,
  onClick,
}) {
  const image = getTravelImage(item.title);

  return (
    <button
      type="button"
      className={`itinerary-item ${isSelected ? 'is-selected' : ''} ${
        isHighlighted ? 'is-overlap-highlight' : ''
      }`}
      onClick={onClick}
    >
      <img className="item-thumbnail" src={image.url} alt={image.alt} />
      <div className="time-block">
        <span>{formatDateTime(item.startDateTime)}</span>
        <span>{formatDateTime(item.endDateTime)}</span>
      </div>
      <div className="item-details">
        <h3>{item.title}</h3>
        {hasOpenConflict && <span className="conflict-badge">Open conflict</span>}
      </div>
    </button>
  );
}

export default ItineraryCard;
