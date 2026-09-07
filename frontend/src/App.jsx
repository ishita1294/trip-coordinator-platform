import { useEffect, useState } from 'react';
import ConflictTable from './components/ConflictTable.jsx';
import ItineraryCard from './components/ItineraryCard.jsx';
import ItineraryDetailModal from './components/ItineraryDetailModal.jsx';

const itineraryUrl = '/api/trips/1/itinerary/items';
const conflictsUrl = '/api/trips/1/members/1/itinerary/conflicts';

function App() {
  const [items, setItems] = useState([]);
  const [conflicts, setConflicts] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedItem, setSelectedItem] = useState(null);
  const [selectedConflict, setSelectedConflict] = useState(null);

  useEffect(() => {
    async function loadItinerary() {
      try {
        const [itemsResponse, conflictsResponse] = await Promise.all([
          fetch(itineraryUrl),
          fetch(conflictsUrl),
        ]);

        if (!itemsResponse.ok || !conflictsResponse.ok) {
          throw new Error('Unable to load itinerary data.');
        }

        const itemsData = await itemsResponse.json();
        const conflictsData = await conflictsResponse.json();

        setItems(Array.isArray(itemsData) ? itemsData : []);
        setConflicts(Array.isArray(conflictsData) ? conflictsData : []);
      } catch (loadError) {
        setError(loadError.message || 'Something went wrong.');
      } finally {
        setIsLoading(false);
      }
    }

    loadItinerary();
  }, []);

  const openConflicts = conflicts.filter((conflict) => conflict.status === 'OPEN');
  const sortedItems = [...items].sort(
    (first, second) => new Date(first.startDateTime) - new Date(second.startDateTime),
  );

  const conflictedItemIds = new Set(
    openConflicts.flatMap((conflict) =>
      [conflict.firstItemId, conflict.secondItemId].filter(Boolean),
    ),
  );

  const selectedConflictItemIds = new Set(
    selectedConflict
      ? [selectedConflict.firstItemId, selectedConflict.secondItemId].filter(Boolean)
      : [],
  );

  const selectedItemConflicts = selectedItem
    ? openConflicts.filter(
        (conflict) =>
          conflict.firstItemId === selectedItem.id ||
          conflict.secondItemId === selectedItem.id,
      )
    : [];

  return (
    <main className="app-shell">
      <section className="page-header">
        <div>
          <p className="eyebrow">Trip 1</p>
          <h1>Itinerary</h1>
        </div>
        <div className="summary">
          <span>{sortedItems.length} items</span>
          <span>
            {openConflicts.length}{' '}
            {openConflicts.length === 1 ? 'open conflict' : 'open conflicts'}
          </span>
        </div>
      </section>

      {isLoading && <p className="status-message">Loading itinerary...</p>}
      {error && <p className="status-message error">{error}</p>}

      {!isLoading && !error && (
        <>
          <section className="content-section">
            <h2>Schedule</h2>
            <div className="timeline">
              {sortedItems.length === 0 && (
                <p className="empty-message">No itinerary items found.</p>
              )}

              {sortedItems.map((item) => (
                <ItineraryCard
                  key={item.id}
                  item={item}
                  hasOpenConflict={conflictedItemIds.has(item.id)}
                  isSelected={selectedItem?.id === item.id}
                  isHighlighted={selectedConflictItemIds.has(item.id)}
                  onClick={() => setSelectedItem(item)}
                />
              ))}
            </div>
          </section>

          <section className="content-section">
            <h2>Open Conflicts</h2>
            <ConflictTable
              conflicts={openConflicts}
              selectedConflictId={selectedConflict?.id}
              onConflictSelect={setSelectedConflict}
            />
          </section>

          {selectedItem && (
            <ItineraryDetailModal
              item={selectedItem}
              conflicts={selectedItemConflicts}
              onClose={() => setSelectedItem(null)}
            />
          )}
        </>
      )}
    </main>
  );
}

export default App;
