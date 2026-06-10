import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/index.css';

/**
 * A3 PLACEHOLDER ENTRY — chunk C8 owns and replaces this file with the real
 * main.tsx (router, providers, App.tsx — port-map §7 C8). It exists only so
 * the Vite shell builds and serves before product surfaces land. Everything
 * rendered here is token-backed to prove the tokens.css → tailwind pipeline.
 */
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <div className="flex h-full items-center justify-center bg-background font-sans text-foreground">
      <div className="rounded-lg border border-border bg-surface px-6 py-4 shadow-sm">
        <p className="font-heading text-lg">Meridian Substrate</p>
        <p className="mt-1 text-sm text-foreground-muted">
          Frontend shell (chunk A3). Product surfaces land in chunks C7–C9.
        </p>
      </div>
    </div>
  </React.StrictMode>,
);
