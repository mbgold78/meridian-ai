import { Link, useLocation } from 'react-router-dom';

/**
 * Catch-all route target. Without this, unmatched deep links return null
 * from useRoutes and the SPA renders a silent blank page (the backend
 * SpaController forwards unknown paths to index.html, so the router is
 * the only thing that can say "not found").
 */
export function NotFound() {
  const { pathname } = useLocation();
  return (
    <div className="flex h-screen flex-col items-center justify-center gap-4 bg-background text-foreground">
      <p className="text-sm uppercase tracking-widest text-foreground-muted">404</p>
      <h1 className="text-xl font-semibold">There is nothing at {pathname}</h1>
      <div className="flex gap-6 text-sm">
        <Link to="/" className="text-primary transition-colors duration-100 hover:underline">
          Living Canvas
        </Link>
        <Link to="/portfolio" className="text-primary transition-colors duration-100 hover:underline">
          Portfolio
        </Link>
      </div>
    </div>
  );
}
