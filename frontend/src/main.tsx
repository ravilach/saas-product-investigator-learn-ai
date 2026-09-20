import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/global.css';

/**
 * Mounts the app.
 *
 * `StrictMode` is on in development, which double-invokes effects on purpose to surface ones that
 * are not cleanup-safe. That matters more than usual here: this app opens SSE streams, and a stream
 * whose effect does not abort on cleanup shows up immediately as duplicated events rather than as a
 * leak nobody notices until production.
 */
const container = document.getElementById('root');
if (!container) {
  throw new Error('No #root element in index.html - the app has nowhere to mount.');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
