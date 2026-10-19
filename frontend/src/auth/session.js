import {clearUiSession} from '../utils/uiSession.js';
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

const issuer = import.meta.env.VITE_AUTH_ISSUER;
const clientId = import.meta.env.VITE_AUTH_CLIENT_ID;
const domain = import.meta.env.VITE_AUTH_DOMAIN;
const scope = import.meta.env.VITE_AUTH_API_SCOPE;
export let configurationError = !issuer || !clientId || !domain || !scope
  ? 'Sign-in is not configured. The application administrator must supply the Cognito settings.' : null;
export let manager = null;
try {
  if (!configurationError && (![issuer,domain].every(value => {
    const url = new URL(value);return url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash;
  }))) configurationError = 'Sign-in configuration must use valid HTTPS identity endpoints.';
  if (!configurationError) manager = new UserManager({
  authority: issuer, client_id: clientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code', scope: `openid email profile ${scope}`,
  automaticSilentRenew: true, revokeTokensOnSignout: true,
  // Tab-scoped session restoration; never localStorage. PKCE/state validation stays library-owned.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  metadata: {
    issuer, authorization_endpoint: `${domain}/oauth2/authorize`,
    token_endpoint: `${domain}/oauth2/token`, userinfo_endpoint: `${domain}/oauth2/userInfo`,
    revocation_endpoint: `${domain}/oauth2/revoke`, jwks_uri: `${issuer}/.well-known/jwks.json`,
  },
  });
} catch { configurationError = 'Sign-in configuration is invalid. Contact the application administrator.'; }
let initialization;
export function initializeSession() {
  if (!initialization) initialization = (async () => {
    if (!manager) return null;
    if (window.location.pathname === '/auth/callback') {
      clearUiSession(); // Interactive login is a new temporary UI session, even for the same account.
      try { return await manager.signinRedirectCallback(); }
      finally { window.history.replaceState({}, '', '/'); }
    }
    const user = await manager.getUser();
    if (!user) {clearUiSession();return null;}
    if (!user.expired) return user;
    try { return await manager.signinSilent(); }
    catch { clearUiSession();await manager.removeUser(); return null; }
  })();
  return initialization;
}
let renewal;
export async function accessToken() {
  if (!manager) throw new Error(configurationError);
  let user = await manager.getUser();
  if (!user) throw new Error('Sign in to continue.');
  if (user.expired) {
    renewal ??= manager.signinSilent().finally(() => { renewal = null; });
    user = await renewal;
  }
  return user.access_token;
}
export async function logout() {
  clearUiSession();
  if (!manager) return;
  try { await manager.revokeTokens(['refresh_token']); }
  finally {
    try {await manager.removeUser();} finally {clearUiSession();}
    window.location.assign(`${domain}/logout?client_id=${encodeURIComponent(clientId)}&logout_uri=${encodeURIComponent(window.location.origin + '/')}`);
  }
}
