// From 'vitest/config' rather than 'vite' so the `test` block below is type-checked. Vitest no
// longer augments Vite's own config type, so importing from 'vite' silently stops checking it.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Vite configuration for the frontend.
 *
 * Two decisions here are worth the comments they carry: the dev proxy, and the manual chunk split.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Everything under /api and /actuator is proxied to the backend so the browser sees a single
    // origin in development, exactly as it does in the packaged image where Spring serves the built
    // assets itself. The backend's CORS_ALLOWED_ORIGINS default would also make direct cross-origin
    // calls work, but then dev and production would differ in a way that only shows up in the one
    // place it is most annoying to debug: cookies, redirects, and streamed responses.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
      '/swagger-ui': { target: 'http://localhost:8080', changeOrigin: true },
      '/v3/api-docs': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    // Route-level code-splitting (see PERFORMANCE) comes from the React.lazy() calls in routes.tsx;
    // Vite emits one chunk per lazy import automatically. What it does *not* do automatically is keep
    // the vendor libraries out of the entry chunk, so the split below makes the initial download the
    // shell plus its dependencies rather than the shell plus every page's dependencies too.
    rollupOptions: {
      output: {
        // The subpaths are listed explicitly, not just the bare package names: Rollup resolves each
        // entry here to one module, and `react-dom` resolves to `react-dom/index.js` - a different
        // module from the `react-dom/client` that main.tsx actually imports. Listing only the bare
        // name leaves the 130kB of react-dom sitting in the entry chunk, which looks like the split
        // worked right up until you check the sizes.
        manualChunks: {
          react: [
            'react',
            'react/jsx-runtime',
            'react-dom',
            'react-dom/client',
            'react-router-dom',
          ],
          query: ['@tanstack/react-query'],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    css: true,
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
