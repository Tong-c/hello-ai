# 工具调用状态流 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让聊天页面在 assistant 流式回复期间，按时间顺序展示工具调用的开始、执行中、成功、失败状态，并将这些状态附着在当前 assistant 消息内部。

**Architecture:** 后端继续复用现有 `/api/chat/stream` SSE 通道，在 `ChatStreamEvent` 中加入 `toolCallId`，并把工具调用改造成带生命周期的状态事件。前端把原先的 `toolEvents[]` 追加日志模型改为 `toolCalls[]` 聚合模型，用 `toolCallId` 持续更新同一调用步骤的状态，再把步骤列表渲染到 assistant 正文上方。

**Tech Stack:** Spring Boot, Spring AI, Reactor Flux/SSE, Vue 3, Vite, 原生 Fetch Streaming, CSS

---

### Task 1: 后端工具事件协议改造

**Files:**
- Modify: `src/main/java/com/tc/ai/api/ChatStreamEvent.java`
- Modify: `src/main/java/com/tc/ai/service/ChatToolEventBridge.java`

- [ ] **Step 1: 扩展 `ChatStreamEvent`，加入 `toolCallId` 字段**

```java
public record ChatStreamEvent(
        String eventType,
        String content,
        String toolCallId,
        String toolName,
        String status,
        String metadata
) {
    public static ChatStreamEvent token(String content) {
        return new ChatStreamEvent("token", content, null, null, "streaming", null);
    }

    public static ChatStreamEvent tool(
            String toolCallId,
            String toolName,
            String content,
            String status,
            String metadata
    ) {
        return new ChatStreamEvent("tool", content, toolCallId, toolName, status, metadata);
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", "", null, null, "completed", null);
    }

    public static ChatStreamEvent error(String content) {
        return new ChatStreamEvent("error", content, null, null, "failed", null);
    }
}
```

- [ ] **Step 2: 调整 `ChatToolEventBridge` 的发布签名，强制传入 `toolCallId`**

```java
public void publishToolEvent(
        String toolCallId,
        String toolName,
        String content,
        String status,
        String metadata
) {
    Sinks.Many<ChatStreamEvent> sink = CURRENT_SINK.get();
    if (sink == null) {
        return;
    }
    sink.tryEmitNext(ChatStreamEvent.tool(toolCallId, toolName, content, status, metadata));
}
```

- [ ] **Step 3: 搜索并修正所有旧的 `publishToolEvent(...)` 调用点，确保后续编译不会因签名不匹配失败**

Run: `rg "publishToolEvent\\(" src/main/java`
Expected: 只剩下 `CurrentTimeTools.java` 和 `CalculatorTools.java` 这两个调用点等待下一任务改造

- [ ] **Step 4: 提交当前协议层改动**

```bash
git add src/main/java/com/tc/ai/api/ChatStreamEvent.java src/main/java/com/tc/ai/service/ChatToolEventBridge.java
git commit -m "refactor: add tool call id to chat stream events"
```

### Task 2: 后端工具生命周期事件改造

**Files:**
- Modify: `src/main/java/com/tc/ai/tool/CurrentTimeTools.java`
- Modify: `src/main/java/com/tc/ai/tool/CalculatorTools.java`

- [ ] **Step 1: 为时间工具增加统一的生命周期事件发送逻辑**

```java
public TimeResponse getCurrentTime() {
    String toolCallId = UUID.randomUUID().toString();
    chatToolEventBridge.publishToolEvent(toolCallId, "getCurrentTime", "", "started", "");

    try {
        chatToolEventBridge.publishToolEvent(toolCallId, "getCurrentTime", "", "running", "");
        ZonedDateTime now = ZonedDateTime.now(DEFAULT_ZONE);
        TimeResponse response = new TimeResponse(
                now.format(DATE_TIME_FORMATTER),
                now.toLocalDate().toString(),
                DEFAULT_ZONE.getId(),
                now.getOffset().toString(),
                Instant.now().toString()
        );
        chatToolEventBridge.publishToolEvent(toolCallId, "getCurrentTime", "", "succeeded", "");
        return response;
    } catch (RuntimeException ex) {
        chatToolEventBridge.publishToolEvent(toolCallId, "getCurrentTime", ex.getMessage(), "failed", "");
        throw ex;
    }
}
```

- [ ] **Step 2: 为计算器工具增加同样的生命周期事件发送逻辑**

```java
public CalculationResult calculate(BigDecimal left, String operation, BigDecimal right) {
    String toolCallId = UUID.randomUUID().toString();
    chatToolEventBridge.publishToolEvent(toolCallId, "calculate", "", "started", "");

    try {
        chatToolEventBridge.publishToolEvent(toolCallId, "calculate", "", "running", "");
        String normalizedOperation = normalizeOperation(operation);
        BigDecimal result = switch (normalizedOperation) {
            case "add" -> left.add(right, MATH_CONTEXT);
            case "subtract" -> left.subtract(right, MATH_CONTEXT);
            case "multiply" -> left.multiply(right, MATH_CONTEXT);
            case "divide" -> divide(left, right);
            case "power" -> power(left, right);
            default -> throw new IllegalArgumentException("unsupported operation: " + operation);
        };
        CalculationResult calculationResult = new CalculationResult(
                format(left),
                normalizedOperation,
                format(right),
                format(result)
        );
        chatToolEventBridge.publishToolEvent(toolCallId, "calculate", "", "succeeded", "");
        return calculationResult;
    } catch (RuntimeException ex) {
        chatToolEventBridge.publishToolEvent(toolCallId, "calculate", ex.getMessage(), "failed", "");
        throw ex;
    }
}
```

- [ ] **Step 3: 补充 `UUID` import 并保留现有日志，确保日志和 SSE 事件都能反映工具执行情况**

```java
import java.util.UUID;
```

- [ ] **Step 4: 运行后端编译验证，确保协议字段和工具调用签名一致**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS 或命令正常退出且没有 Java 编译错误

- [ ] **Step 5: 提交工具生命周期改动**

```bash
git add src/main/java/com/tc/ai/tool/CurrentTimeTools.java src/main/java/com/tc/ai/tool/CalculatorTools.java
git commit -m "feat: emit tool lifecycle events"
```

### Task 3: 前端流事件解析与消息状态模型改造

**Files:**
- Modify: `frontend/src/lib/stream-chat.js`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: 保持 `stream-chat.js` 的事件分发结构，但让工具事件完整透传 `toolCallId` 等字段**

```js
if (event.data.eventType === 'token') {
  onToken?.(event.data.content || '');
} else if (event.data.eventType === 'tool') {
  onTool?.({
    toolCallId: event.data.toolCallId || '',
    toolName: event.data.toolName || 'tool',
    status: event.data.status || 'started',
    content: event.data.content || '',
    metadata: event.data.metadata || '',
  });
} else if (event.data.eventType === 'error') {
  onError?.(event.data.content || 'The server returned an error.');
}
```

- [ ] **Step 2: 把 assistant 消息的状态结构从 `toolEvents` 切换到 `toolCalls`**

```js
const assistantMessage = {
  id: crypto.randomUUID(),
  role: 'assistant',
  content: '',
  attachmentName: null,
  toolCalls: [],
};
```

- [ ] **Step 3: 在 `onTool` 中实现基于 `toolCallId` 的聚合更新逻辑**

```js
onTool(event) {
  if (!event.toolCallId) {
    return;
  }

  const existing = assistantMessage.toolCalls.find(
    (toolCall) => toolCall.id === event.toolCallId,
  );

  if (!existing) {
    assistantMessage.toolCalls.push({
      id: event.toolCallId,
      toolName: event.toolName || 'tool',
      status: event.status || 'started',
      errorMessage: event.status === 'failed' ? (event.content || '工具调用失败') : '',
      startedAt: Date.now(),
      updatedAt: Date.now(),
    });
    return;
  }

  existing.status = event.status || existing.status;
  existing.updatedAt = Date.now();
  if (existing.status === 'failed') {
    existing.errorMessage = event.content || '工具调用失败';
  }
}
```

- [ ] **Step 4: 增加状态文案映射函数，避免模板里直接展示英文状态值**

```js
function getToolStatusLabel(status) {
  switch (status) {
    case 'started':
      return '开始调用';
    case 'running':
      return '调用中';
    case 'succeeded':
      return '成功';
    case 'failed':
      return '失败';
    default:
      return '处理中';
  }
}
```

- [ ] **Step 5: 提交前端状态模型改动**

```bash
git add frontend/src/lib/stream-chat.js frontend/src/App.vue
git commit -m "feat: track tool call status in assistant messages"
```

### Task 4: 前端工具步骤渲染与样式整理

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: 用新的 `toolCalls[]` 渲染 assistant 消息内的步骤列表**

```vue
<div v-if="message.toolCalls?.length" class="tool-calls">
  <div v-for="toolCall in message.toolCalls" :key="toolCall.id" class="tool-call" :data-status="toolCall.status">
    <div class="tool-call-header">
      <span class="tool-badge">{{ toolCall.toolName }}</span>
      <span class="tool-status">{{ getToolStatusLabel(toolCall.status) }}</span>
    </div>
    <p v-if="toolCall.status === 'failed' && toolCall.errorMessage" class="tool-error">
      {{ toolCall.errorMessage }}
    </p>
  </div>
</div>
```

- [ ] **Step 2: 删除旧的 `tool-content` 和 `tool-meta` 依赖，避免继续渲染入参或返回结果风格的信息块**

```vue
<template v-else>
  <div v-if="message.toolCalls?.length" class="tool-calls">
    <div
      v-for="toolCall in message.toolCalls"
      :key="toolCall.id"
      class="tool-call"
      :data-status="toolCall.status"
    >
      <div class="tool-call-header">
        <span class="tool-badge">{{ toolCall.toolName }}</span>
        <span class="tool-status">{{ getToolStatusLabel(toolCall.status) }}</span>
      </div>
      <p v-if="toolCall.status === 'failed' && toolCall.errorMessage" class="tool-error">
        {{ toolCall.errorMessage }}
      </p>
    </div>
  </div>
  <div
    class="markdown-content"
    v-html="renderMarkdown(message.content || (isSending ? '...' : ''))"
  />
</template>
```

- [ ] **Step 3: 在样式中加入基于状态的视觉区分，但保持当前页面风格一致**

```css
.tool-calls {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
}

.tool-call {
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid rgba(148, 163, 184, 0.32);
  background: rgba(226, 232, 240, 0.5);
}

.tool-call[data-status='running'] {
  border-color: rgba(14, 116, 144, 0.35);
  background: rgba(224, 242, 254, 0.75);
}

.tool-call[data-status='succeeded'] {
  border-color: rgba(21, 128, 61, 0.28);
  background: rgba(220, 252, 231, 0.72);
}

.tool-call[data-status='failed'] {
  border-color: rgba(185, 28, 28, 0.24);
  background: rgba(254, 226, 226, 0.8);
}

.tool-call-header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.tool-error {
  margin-top: 6px;
  color: #991b1b;
}
```

- [ ] **Step 4: 运行前端构建，确保模板和脚本没有语法错误**

Run: `npm run build`
Workdir: `frontend`
Expected: Vite build 成功完成，没有 Vue 编译错误

- [ ] **Step 5: 提交工具状态 UI 改动**

```bash
git add frontend/src/App.vue frontend/src/styles.css
git commit -m "feat: render tool status timeline in chat ui"
```

### Task 5: 联调与手工验收

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-04-16-tool-status-stream-design.md`（如实现与 spec 有微调时再更新）

- [ ] **Step 1: 启动后端和前端，执行最小联调**

Run: `./mvnw spring-boot:run`
Expected: 后端启动并监听 `:8080`

Run: `npm run dev`
Workdir: `frontend`
Expected: 前端启动并监听 `:5173`

- [ ] **Step 2: 手工验证 `calculate` 工具的完整状态流**

```text
在页面输入：123 * 45 等于多少？
预期：
1. 当前 assistant 消息立刻出现 calculate 步骤
2. 步骤状态至少经历 开始调用 -> 成功
3. assistant 正文继续正常流式输出
```

- [ ] **Step 3: 手工验证 `getCurrentTime` 工具的完整状态流**

```text
在页面输入：现在几点了？
预期：
1. 当前 assistant 消息立刻出现 getCurrentTime 步骤
2. 步骤状态至少经历 开始调用 -> 成功
3. 不同回复中的工具步骤不会串到上一条消息里
```

- [ ] **Step 4: 手工验证失败状态**

```text
临时把 `CalculatorTools.calculate(...)` 中的成功事件发送替换为：
chatToolEventBridge.publishToolEvent(toolCallId, "calculate", "manual failure for UI verification", "failed", "");

页面输入：123 * 45 等于多少？
预期：
1. 对应步骤状态变为 失败
2. 页面显示简短错误信息
3. 页面不会因为单个失败步骤而整体崩溃
```

- [ ] **Step 5: 更新 README 的功能说明，补一段工具状态流能力说明**

```md
## Tool Status Stream

前端会在 assistant 消息内部实时展示工具调用状态，包括开始调用、调用中、成功、失败。
当前界面只展示工具名和状态，不展示工具入参与返回结果。
```

- [ ] **Step 6: 提交联调与文档改动**

```bash
git add README.md docs/superpowers/specs/2026-04-16-tool-status-stream-design.md
git commit -m "docs: document tool status stream behavior"
```
