# Tool Status Stream Design

## Summary

Add real-time tool execution status to the chat UI so users can see when the model starts a tool call, continues running it, succeeds, or fails.

The status flow should appear inside the current assistant message, be expanded by default, and preserve multiple tool calls in time order within the same reply.

## Goals

- Show tool execution progress while the assistant response is streaming.
- Keep tool state visually attached to the assistant message that triggered it.
- Preserve a clear chronological history when one reply uses multiple tools.
- Keep the UI minimal by showing only tool name, status, and failure detail.

## Non-Goals

- Do not show tool input arguments.
- Do not show tool return payloads.
- Do not add a standalone tool inspector panel.
- Do not add automated test coverage as part of this change.

## Current Context

The backend already exposes `POST /api/chat/stream` and emits `ChatStreamEvent` objects over SSE. The event shape already contains `eventType`, `toolName`, `status`, and `metadata`.

The backend also already has `ChatToolEventBridge`, which can publish tool-related stream events into the same reactive stream used by the assistant response.

The frontend already:

- opens the SSE stream through `frontend/src/lib/stream-chat.js`
- appends assistant text tokens as they arrive
- renders tool events inside the assistant message in `frontend/src/App.vue`

The current gap is that tool events are treated like independent log lines instead of a single tool call lifecycle with state transitions.

## User Experience

### Placement

Tool status appears inside the current assistant message, above or before the streamed markdown answer content.

### Default Visibility

Tool status is expanded by default.

### Multiple Tool Calls

If one assistant reply calls multiple tools, the UI shows them as separate steps in the order they begin. Existing steps remain visible after completion.

### Step Content

Each displayed tool step contains:

- tool name
- current status label
- last update timestamp or equivalent local ordering marker
- failure message when the call fails

No tool inputs or outputs are rendered.

### Status Flow

The frontend should support these states:

- `started`
- `running`
- `succeeded`
- `failed`

User-facing labels can be localized as:

- `开始调用`
- `调用中`
- `成功`
- `失败`

## Backend Design

### Stream Protocol

Keep using the existing `/api/chat/stream` SSE channel. Do not add a second polling or subscription mechanism.

Continue using `eventType = "tool"` for tool lifecycle messages, but extend the payload with a stable `toolCallId`.

Proposed event shape:

```json
{
  "eventType": "tool",
  "toolCallId": "uuid-or-stable-id",
  "toolName": "calculate",
  "status": "started",
  "content": "",
  "metadata": ""
}
```

`content` and `metadata` may remain for backward compatibility, but this feature does not rely on them for display. If needed, `content` can carry a short failure message and otherwise stay empty.

### Lifecycle Rules

For each tool call:

1. Emit `started` before execution begins.
2. Emit `running` when the call is actively executing, if the underlying integration allows a distinct in-progress signal.
3. Emit `succeeded` when the tool finishes successfully.
4. Emit `failed` when the tool throws or the invocation cannot complete.

At minimum, `started` plus one terminal state is required. `running` is optional if the framework does not expose a clean intermediate hook.

### Correlation

`toolCallId` is required so the frontend can update one existing visual step instead of appending duplicate entries for the same call.

The backend must generate a unique `toolCallId` per invocation and emit it for every lifecycle update of that invocation.

### Failure Handling

If a tool call fails:

- emit a `failed` tool event for that `toolCallId`
- include a short readable failure message for UI display
- do not crash frontend rendering because of the failed tool step alone

Model-stream failure remains a separate stream error and should still use the existing error event path.

## Frontend Design

### Message Model

Upgrade the assistant message state from raw `toolEvents[]` append-only entries to `toolCalls[]`, where each item represents one tool invocation lifecycle.

Recommended shape:

```js
{
  id: "tool-call-id",
  toolName: "calculate",
  status: "started",
  errorMessage: "",
  startedAt: 1710000000000,
  updatedAt: 1710000000000
}
```

### Update Rules

When the frontend receives a tool event:

1. Find an existing tool call by `toolCallId`.
2. If none exists, create a new step and append it to the current assistant message.
3. If one exists, update its `status`, `updatedAt`, and `errorMessage` if the new state is `failed`.
4. Preserve array order based on first appearance so the visual list remains chronological.

### Rendering Rules

Each assistant message renders:

- tool steps in chronological order
- the assistant markdown content below them

Each tool step renders:

- tool badge or name
- localized status text
- optional failure text when status is `failed`

Tool steps are always visible by default. There is no collapsed summary-only mode in this change.

### Invalid Events

If an incoming tool event is missing required correlation data, the frontend should ignore it instead of breaking message rendering.

## File Impact

Expected primary files:

- `src/main/java/com/tc/ai/api/ChatStreamEvent.java`
- `src/main/java/com/tc/ai/service/ChatToolEventBridge.java`
- `src/main/java/com/tc/ai/service/SpringAiAgentChatService.java`
- `frontend/src/lib/stream-chat.js`
- `frontend/src/App.vue`
- optionally `frontend/src/styles.css`

## Manual Verification

This change will be validated manually in the browser instead of with automated tests.

Minimum verification flow:

1. Ask a question that triggers `calculate`.
2. Confirm a new tool step appears under the current assistant message as soon as the tool starts.
3. Confirm the same step transitions through lifecycle states instead of duplicating as separate unrelated cards.
4. Confirm the tool step remains visible after success.
5. Ask a question that triggers `getCurrentTime`.
6. Confirm multiple replies each render their own tool steps inside the correct assistant message.
7. Trigger or simulate a tool failure and confirm the step shows `失败` with a brief error message.
8. Confirm the assistant markdown response still streams normally while tool state is shown.

## Scope Notes

This feature is intentionally limited to the existing single-message chat UI and current SSE stream architecture. It does not introduce history persistence, cross-message tool timelines, or developer-facing observability tooling.
