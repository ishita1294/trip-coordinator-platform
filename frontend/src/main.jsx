import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import AuthGate from './auth/AuthGate.jsx';
import './App.css';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <AuthGate>{actor => <App currentUser={actor} />}</AuthGate>
  </StrictMode>,
);
