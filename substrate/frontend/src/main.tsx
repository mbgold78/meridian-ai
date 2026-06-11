import React from 'react';
import ReactDOM from 'react-dom/client';
import { Provider } from 'react-redux';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { store } from '@/app/store';
import { ThemeProvider } from '@/app/theme';
import { AuthProvider } from '@/auth';
import App from './App';
import './styles/index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000,
      retry: 1,
    },
  },
});

// AuthProvider sits OUTSIDE QueryClientProvider: in oidc mode it withholds the
// tree (including ThemeProvider's branding useQuery) until a verified token is
// in the auth runtime, so no API call ever fires unauthenticated. In anonymous
// mode (default) it is a constant-context pass-through — zero behavior change.
// ThemeProvider is nested INSIDE QueryClientProvider because it uses useQuery
// to fetch the active BrandingConfig; it is nested OUTSIDE BrowserRouter
// so navigation never unmounts the theme context.
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Provider store={store}>
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </ThemeProvider>
        </QueryClientProvider>
      </AuthProvider>
    </Provider>
  </React.StrictMode>,
);
