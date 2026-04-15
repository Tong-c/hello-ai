# frontend

Standalone `Vue + Vite` frontend for the AI agent chat UI.

## Commands

```bash
npm install
npm run dev
npm run build
npm run preview
```

## Environment

- `VITE_API_BASE`: optional absolute backend base URL for deployed environments
- `VITE_PROXY_TARGET`: backend target used by the Vite dev proxy

If the frontend is deployed on a different origin from the backend, allow that origin in the backend CORS configuration.
