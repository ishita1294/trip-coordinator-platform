import {readUiSession,writeUiSession,uiKey} from './utils/uiSession.js';
import CreateTrip from './components/CreateTrip.jsx';
import { apiFetch } from './api/authenticatedApi.js';
import { useEffect, useState } from 'react';
import ConflictTable from './components/ConflictTable.jsx';
import ItineraryCard from './components/ItineraryCard.jsx';
import { formatScheduleDate } from './utils/itineraryFormatting.js';
import ItineraryDetailModal from './components/ItineraryDetailModal.jsx';
import TravelDocumentsTab from './components/TravelDocumentsTab.jsx';



function App({ currentUser }) {
  const currentUserId = currentUser.id;
  const [reload, setReload] = useState(0);
  const [trips, setTrips] = useState([]);
  const [selectedTripId, setSelectedTripId] = useState(null);
  const [membershipActor,setMembershipActor]=useState(null);
  const selectionKey=uiKey(currentUserId,'trip');
  const [tripError, setTripError] = useState('');
  const [tripsLoading, setTripsLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    setTripsLoading(true);setTripError('');
    async function loadTrips() {
      try {
        const response = await apiFetch('/api/trips', { signal: controller.signal });
        if (!response.ok) throw new Error('Unable to load trips.');
        const availableTrips = await response.json();
        const memberships = await Promise.all(availableTrips.map(async (trip) => {
          const response = await apiFetch(`/api/trips/${trip.id}/members`, { signal: controller.signal });
          if(response.status===403 || response.status===404)return null;
          if (!response.ok) throw new Error('Unable to load trip memberships.');
          const members = await response.json();
          const membership = members.find((member) => member.user.id === currentUserId);
          return membership ? { ...trip, memberId: membership.id } : null;
        }));
        if (controller.signal.aborted) return;
        const userTrips = memberships.filter(Boolean);
        setTrips(userTrips);
        const saved=readUiSession(selectionKey)?.tripId;
        setSelectedTripId(previous=>membershipActor===currentUserId && userTrips.some(trip=>trip.id===previous)?previous:userTrips.some(trip=>trip.id===saved)?saved:userTrips.length===1?userTrips[0].id:null);
        setMembershipActor(currentUserId);
      } catch (error) {
        if (!controller.signal.aborted) {setTrips([]);setMembershipActor(null);setTripError(error.message || 'Unable to load trips.');}
      } finally {
        if (!controller.signal.aborted) setTripsLoading(false);
      }
    }
    loadTrips();
    return () => controller.abort();
  }, [reload, currentUserId]);

  useEffect(()=>{
    if(!tripsLoading && membershipActor===currentUserId && trips.some(trip=>trip.id===selectedTripId))writeUiSession(selectionKey,{tripId:selectedTripId});
  },[tripsLoading,membershipActor,currentUserId,selectedTripId,trips,selectionKey]);

  const selectedTrip = membershipActor===currentUserId && trips.find((trip) => trip.id === selectedTripId);
  if (!selectedTrip) {
    return <main className="app-shell"><p className={`status-message${tripError ? ' error' : ''}`}>
      {tripsLoading ? 'Loading trips...' : tripError || (trips.length?'Choose a trip to continue.':'No trips found for this user.')}
    </p>{!tripsLoading && membershipActor===currentUserId && trips.length>0 && <label>Your trip<select value="" onChange={event=>setSelectedTripId(Number(event.target.value))}><option value="">Choose a trip</option>{trips.map(trip=><option key={trip.id} value={trip.id}>{trip.name}</option>)}</select></label>}{!tripsLoading && <CreateTrip onCreated={() => setReload(value=>value+1)} />}</main>;
  }
  return <><CreateTrip onCreated={() => setReload(value=>value+1)} />
    <TripWorkspace key={`${currentUserId}:${selectedTrip.id}`} trip={selectedTrip} trips={trips}
                        actorId={currentUserId} onTripChange={setSelectedTripId} /></>;
}

function TripWorkspace({ trip, trips, onTripChange, actorId }) {
  const tripId = trip.id;
  const memberId = trip.memberId;
  const itineraryUrl = `/api/trips/${tripId}/itinerary/items`;
  const conflictsUrl = `/api/trips/${tripId}/members/${memberId}/itinerary/conflicts`;
  const tabKey=uiKey(actorId,'tab',tripId);
  const [activeTab,setActiveTab]=useState(()=>{const saved=readUiSession(tabKey)?.tab;return ['itinerary','documents'].includes(saved)?saved:'itinerary';});
  useEffect(()=>{writeUiSession(tabKey,{tab:activeTab});},[tabKey,activeTab]);
  const [items, setItems] = useState([]);
  const [conflicts, setConflicts] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedItem, setSelectedItem] = useState(null);
  const [selectedConflict, setSelectedConflict] = useState(null);
  const [resolutionSuggestions, setResolutionSuggestions] = useState({});
  const [suggestionLoadingId, setSuggestionLoadingId] = useState(null);
  const [suggestionErrors, setSuggestionErrors] = useState({});
  const [visibleSuggestionConflictId, setVisibleSuggestionConflictId] = useState(null);
  const [applyLoadingId, setApplyLoadingId] = useState(null);
  const [applyErrors, setApplyErrors] = useState({});

  useEffect(() => {
    loadItinerary();
  }, []);


    async function loadItinerary() {
      try {
        const [itemsResponse, conflictsResponse] = await Promise.all([
          apiFetch(itineraryUrl),
          apiFetch(conflictsUrl),
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


  const openConflicts = conflicts.filter((conflict) => conflict.status === 'OPEN');
  const sortedItems = [...items].sort(
    (first, second) => String(first.startDateTime || '').localeCompare(String(second.startDateTime || '')),
  );

  const itemsByDate = new Map();
  for (const item of sortedItems) {
    const date = item.startDateTime?.split('T')[0] || '';
    if (!itemsByDate.has(date)) itemsByDate.set(date, []);
    itemsByDate.get(date).push(item);
  }

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

  async function suggestResolutions(conflictId) {

    setVisibleSuggestionConflictId(conflictId);
    setSuggestionLoadingId(conflictId);
    setSuggestionErrors((current) => ({
      ...current,
      [conflictId]: null,
    }));
    setApplyErrors((current) => ({
      ...current,
      [conflictId]: null,
    }));

    try {
      const response = await apiFetch(
          `/api/trips/${tripId}/members/${memberId}/itinerary/conflicts/${conflictId}/suggestions`,
          {
            method: 'POST',
          },
      );

      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.detail || problem?.message || 'Unable to generate resolution suggestions.');
      }

      const data = await response.json();
      if (Array.isArray(data.options) && data.options.length === 0) {
        setSuggestionErrors(current => ({...current, [conflictId]: data.explanation || 'No verified schedule move is available.'}));
      }

      setResolutionSuggestions((current) => ({
        ...current,
        [conflictId]: Array.isArray(data.options) ? data.options : [],
      }));
    } catch (suggestionRequestError) {
      setSuggestionErrors((current) => ({
        ...current,
        [conflictId]:
        suggestionRequestError.message ||
        'Unable to generate resolution suggestions.',
      }));
    } finally {
      setSuggestionLoadingId(null);
    }
  }

  function openItineraryItem(item) {
    setSuggestionErrors({});
    setApplyErrors({});
    setSelectedItem(item);
  }
  async function applyConflictResolution(conflictId, suggestion) {

    setApplyLoadingId(conflictId);
    setApplyErrors((current) => ({
      ...current,
      [conflictId]: null,
    }));
    try {
      const response = await apiFetch(
          `/api/trips/${tripId}/members/${memberId}/itinerary/conflicts/${conflictId}/apply`,
          {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              itineraryItemId: suggestion.itemId,
              proposedStartDateTime: suggestion.proposedStartDateTime,
              proposedEndDateTime: suggestion.proposedEndDateTime,
            }),
          },
      );

      if (!response.ok) {
        const errorData = await response.json().catch(() => null);

        throw new Error(
            errorData?.detail ||
            errorData?.message ||
            'Unable to apply resolution suggestion.'
        );
      }

      const updatedItem = await response.json();

      setSelectedItem((currentItem) =>
          currentItem?.id === updatedItem.id
              ? updatedItem
              : currentItem
      );

      await loadItinerary();

      return updatedItem;
    } catch (error) {
      setApplyErrors((current) => ({
        ...current,
        [conflictId]:
        error.message || 'Unable to apply resolution suggestion.',
      }));
      setResolutionSuggestions((current) => ({
        ...current,
        [conflictId]: [],
      }));
      await loadItinerary();
    } finally {
      setApplyLoadingId(null);
    }
  }

  return (
    <main className="app-shell">
      <label>Your trip <select value={tripId} onChange={(event) => onTripChange(Number(event.target.value))}>
        {trips.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
      </select></label>

      <section className="page-header">
        <div>
          <p className="eyebrow">Trip {tripId}</p>
          <h1>{activeTab === 'itinerary' ? 'Itinerary' : 'Travel Documents'}</h1>
        </div>
        <div className="summary" hidden={activeTab !== 'itinerary'}>
          <span>{sortedItems.length} items</span>
          <span>
            {openConflicts.length}{' '}
            {openConflicts.length === 1 ? 'open conflict' : 'open conflicts'}
          </span>
        </div>
      </section>

      <nav className="trip-tabs" aria-label="Trip sections">
        <button type="button" aria-pressed={activeTab === 'itinerary'}
                onClick={() => setActiveTab('itinerary')}>Itinerary</button>
        <button type="button" aria-pressed={activeTab === 'documents'}
                onClick={() => setActiveTab('documents')}>Travel Documents</button>
      </nav>

      {activeTab === 'documents' && <TravelDocumentsTab tripId={tripId} onCommitmentSaved={loadItinerary} />}

      <div hidden={activeTab !== 'itinerary'}>
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

              {[...itemsByDate].map(([date, dayItems]) => (
                <section className="itinerary-day" key={date} aria-label={date ? formatScheduleDate(date, true) : 'Unscheduled'}>
                  <div className="itinerary-day-heading">
                    <h3>{date ? formatScheduleDate(date, true) : 'Unscheduled'}</h3>
                    <span>{dayItems.length} {dayItems.length === 1 ? 'item' : 'items'}</span>
                  </div>
                  <div className="itinerary-day-items">
                    {dayItems.map((item) => (
                      <ItineraryCard
                        key={item.id}
                        item={item}
                        hasOpenConflict={conflictedItemIds.has(item.id)}
                        isSelected={selectedItem?.id === item.id}
                        isHighlighted={selectedConflictItemIds.has(item.id)}
                        onClick={() => openItineraryItem(item)}
                      />
                    ))}
                  </div>
                </section>
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
                resolutionSuggestions={resolutionSuggestions}
                visibleSuggestionConflictId={visibleSuggestionConflictId}
                suggestionLoadingId={suggestionLoadingId}
                applyLoadingId={applyLoadingId}
                suggestionErrors={suggestionErrors}
                applyErrors={applyErrors}
                onSuggestResolutions={suggestResolutions}
                onApplyResolution={applyConflictResolution}
                onClose={() => {
                  setSelectedItem(null);
                  setVisibleSuggestionConflictId(null);
                  setSuggestionErrors({});
                  setApplyErrors({});
                }}
            />
          )}
        </>
      )}
      </div>
    </main>
  );
}



export default App;
