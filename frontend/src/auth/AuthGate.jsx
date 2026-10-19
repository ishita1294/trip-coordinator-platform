import {synchronizeUiPrincipal,clearUiSession} from '../utils/uiSession.js';
import { useEffect, useState, useRef } from 'react';
import { configurationError, initializeSession, manager, logout } from './session.js';
import { apiFetch } from '../api/authenticatedApi.js';

export default function AuthGate({ children }) {
  const generation = useRef(0);
  const verifiedIdentity=useRef(null);
  const [session, setSession] = useState(null);
  const [actor, setActor] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(configurationError || '');
  useEffect(() => {
    let active = true;
    async function load(user) {
      if (!active) return;
      const request = ++generation.current;
      const identity=user?.profile?.sub?JSON.stringify([user.profile.iss || '',user.profile.sub]):null;
      synchronizeUiPrincipal(identity);
      setSession(user);
      // Silent renewal for the same principal must not unmount its workspace. Still revalidate /me.
      if(!identity || identity!==verifiedIdentity.current){setActor(null);setLoading(true);verifiedIdentity.current=null;}
      if (user) {
        try {
          const response = await apiFetch('/api/me');
          if (!response.ok) throw new Error(response.status === 409
            ? 'This account needs an administrator-reviewed link to your existing user. Please contact the operator.'
            : 'Unable to initialize your account.');
          const me = await response.json();
          if (active && request === generation.current) {verifiedIdentity.current=identity;setActor(me);}
        } catch (failure) { if (active && request === generation.current) {verifiedIdentity.current=null;setActor(null);setError(failure.message);} }
      }
      if (active && request === generation.current) setLoading(false);
    }
    const initialGeneration=generation.current;
    initializeSession().then(user => { if (generation.current === initialGeneration) return load(user); }).catch(() => { if(active) {setError('Sign-in could not be completed. Please try again.');setLoading(false);} });
    const loaded = user => { setError(''); load(user); };
    const unloaded = () => { if(!active)return;clearUiSession();++generation.current; if(active) {verifiedIdentity.current=null;setSession(null);setActor(null);setLoading(false);} };
    const expired = () => { unloaded(); setError('Your session has expired. Please sign in again.'); };
    manager?.events.addUserLoaded(loaded);
    manager?.events.addUserUnloaded(unloaded);
    manager?.events.addSilentRenewError(expired);
    return () => { active=false; manager?.events.removeUserLoaded(loaded);manager?.events.removeUserUnloaded(unloaded);manager?.events.removeSilentRenewError(expired); };
  }, []);
  if (actor && session) return <div key={`${session.profile.sub}:${actor.id}`}>
    <div className="account-bar"><span>{actor.name}</span><button type="button" onClick={logout}>Sign out / switch account</button></div>
    {children(actor)}
  </div>;
  return <main className="app-shell"><section className="auth-panel">
    <h1>Travel Intelligence</h1><p>Sign in to access your trips and shared travel plans.</p>
    {loading ? <p role="status">Restoring your session…</p> : <>
      {error && <p role="alert" className="status-message error">{error}</p>}
      {!configurationError && <button type="button" onClick={() => manager.signinRedirect().catch(() => setError('Unable to start sign-in.'))}>Sign in</button>}
      {session && <button type="button" onClick={logout}>Sign out / switch account</button>}
    </>}
  </section></main>;
}
