import { accessToken, manager } from '../auth/session.js';

/** Bearer tokens are sent only to this application's same-origin /api boundary. */
export async function apiFetch(input, options = {}) {
  const url = new URL(input, window.location.origin);
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/'))
    throw new Error('Authenticated requests must target the application API.');
  const headers = new Headers(options.headers);
  const token = await accessToken();
  headers.set('Authorization', `Bearer ${token}`);
  const response = await fetch(url, { ...options, headers, credentials: 'omit' });
  if (response.status === 401) {
    // A late 401 from an old account must not sign out a newer session.
    const current = await manager?.getUser();
    if (current?.access_token === token) await manager.removeUser();
    throw new Error('Your session has ended. Sign in again.');
  }
  // Never retry mutations automatically. Preserve server permission details for callers.
  return response;
}
