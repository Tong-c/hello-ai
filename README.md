# hello-ai

Spring Boot backend with a separate `Vue + Vite` frontend for a personal AI agent UI.

## Structure

- `src/main/java`: Spring Boot backend
- `src/main/resources`: backend configuration
- `frontend`: standalone Vue frontend

## Backend

Run the backend from IDEA with the project JDK set to Java 21.

Environment variables:

- `OPENAI_API_KEY`
- `OPENAI_BASE_URL` (optional)
- `OPENAI_CHAT_MODEL` (optional)
- `app.cors.allowed-origins` or `APP_CORS_ALLOWED_ORIGINS` (optional, defaults to `http://localhost:5173`)

Primary endpoints:

- `GET /api/health`
- `POST /api/chat`
- `POST /api/chat/stream`

`/api/chat` and `/api/chat/stream` accept `multipart/form-data` with:

- `message`: text input
- `file`: optional single upload

## Simple Tool Demo

The backend registers a minimal Spring AI tool named `getCurrentTime`.
Ask the model questions such as `现在几点了？` or `今天日期是什么？` and it can call the tool to return realtime time information for `Asia/Shanghai`.

It also registers a `calculate` tool for basic arithmetic. Ask questions such as `123 * 45 等于多少？` or `2 的 8 次方是多少？` and the model can call the calculator tool instead of relying on free-form reasoning.

## Frontend

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server proxies `/api` to `http://localhost:8080` by default.

To point the frontend at a deployed backend, set `VITE_API_BASE` before building or running the frontend.
