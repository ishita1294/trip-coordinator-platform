import { useState } from 'react';
import { apiFetch } from '../api/authenticatedApi.js';
import { readResponse } from '../api/travelDocumentsApi.js';
export default function CreateTrip({ onCreated }) {
  const [error,setError]=useState('');const [busy,setBusy]=useState(false);
  async function submit(event) {
    event.preventDefault();setBusy(true);setError('');
    const form=new FormData(event.currentTarget);
    try {
      const trip=await readResponse(await apiFetch('/api/trips',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({name:form.get('name'),startDate:form.get('startDate'),endDate:form.get('endDate'),tripType:form.get('tripType')})}), 'Unable to create trip.');
      onCreated(trip);
    } catch(failure){setError(failure.message);}finally{setBusy(false);}
  }
  return <details className="create-trip"><summary>Create a trip</summary><form onSubmit={submit}>
    <label>Trip name <input name="name" required maxLength={255}/></label>
    <label>Start date <input type="date" name="startDate" required/></label>
    <label>End date <input type="date" name="endDate" required/></label>
    <label>Travel party <select name="tripType"><option value="SOLO">Personal trip</option><option value="GROUP">Group trip</option></select></label>
    <button disabled={busy}>{busy?'Creating…':'Create trip'}</button>{error&&<p role="alert">{error}</p>}
  </form></details>;
}
