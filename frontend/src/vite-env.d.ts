/// <reference types="vite/client" />

/**
 * Environment variables this app reads. Vite only exposes `VITE_`-prefixed values to the client
 * bundle, which is the guardrail that keeps a server-side secret from being inlined into JavaScript
 * that anyone can view - the same reason no LLM API key ever reaches the browser here.
 */
interface ImportMetaEnv {
  /**
   * Absolute base URL for the API, e.g. `https://api.example.com`. Leave unset for the normal case
   * where the frontend and backend share an origin (Vite's dev proxy, or the packaged image).
   */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
