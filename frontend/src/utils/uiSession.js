// Clear only disposable UI choices, never OIDC state, saved preferences or domain data.
export function clearUiSession() {
  try {for(const key of Object.keys(window.sessionStorage))if(key.startsWith('travel-ui:'))window.sessionStorage.removeItem(key);}catch{/* Storage may be unavailable. */}
}
export function synchronizeUiPrincipal(identity) {
  if(!identity){clearUiSession();return;}
  try {
    const previous=window.sessionStorage.getItem('travel-ui:principal');
    if(previous && previous!==identity)clearUiSession();
    window.sessionStorage.setItem('travel-ui:principal',identity);
  }catch{/* Membership validation and actor-scoped keys remain authoritative. */}
}
// Tab-scoped UI preferences only. Never store tokens, booking records or planning results here.
export const uiKey=(actorId,section,tripId='')=>actorId==null?null:`travel-ui:v1:${actorId}:${section}:${tripId}`;
export function readUiSession(key) {
  if(!key)return null;
  try {const value=JSON.parse(window.sessionStorage.getItem(key));return value && typeof value==='object' && !Array.isArray(value)?value:null;}catch{return null;}
}
export function writeUiSession(key,value) {
  if(!key)return;
  try {window.sessionStorage.setItem(key,JSON.stringify(value));}catch{/* UI remains usable when tab storage is unavailable. */}
}
