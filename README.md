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

后端注册了一个最小可用的 Spring AI 工具 `getCurrentTime`。
你可以直接问 `现在几点了？`、`今天日期是什么？` 之类的问题，模型会调用这个工具来返回 `Asia/Shanghai` 时区的实时信息。

后端还注册了一个 `calculate` 工具用于基础计算。你可以问 `123 * 45 等于多少？` 或 `2 的 8 次方是多少？`，模型会优先调用计算器工具，而不是直接自由生成结果。

## Frontend

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server proxies `/api` to `http://localhost:8080` by default.

To point the frontend at a deployed backend, set `VITE_API_BASE` before building or running the frontend.
