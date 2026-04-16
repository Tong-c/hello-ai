# 工具调用状态流设计

## 概述

为聊天页面增加工具调用的实时状态展示，让用户在助手回复流式生成期间，能够看到模型何时开始调用工具、何时处于执行中、何时成功、何时失败。

工具状态需要展示在当前 assistant 消息内部，默认展开，并且在同一条回复里按时间顺序保留多次工具调用的步骤记录。

## 目标

- 在 assistant 正文流式输出时，同步展示工具执行进度。
- 让工具状态与触发它的 assistant 消息保持同一上下文。
- 当一条回复内调用多个工具时，保留清晰的时间顺序。
- 保持界面简洁，只展示工具名、状态和失败信息。

## 非目标

- 不展示工具入参。
- 不展示工具返回结果。
- 不新增独立的工具执行面板。
- 这次改动不包含自动化测试。

## 当前项目上下文

后端已经提供 `POST /api/chat/stream`，并通过 SSE 向前端发送 `ChatStreamEvent`。

当前事件结构已经包含：

- `eventType`
- `toolName`
- `status`
- `metadata`

后端也已经有 `ChatToolEventBridge`，可以把工具相关事件注入到与 assistant 正文相同的响应流中。

前端当前已经具备以下基础能力：

- 通过 `frontend/src/lib/stream-chat.js` 建立 SSE 流
- 按 token 追加 assistant 正文
- 在 `frontend/src/App.vue` 中把工具事件渲染到 assistant 消息内

当前缺口在于：工具事件仍然被当作彼此独立的日志条目追加，而不是“同一次工具调用生命周期的多次状态更新”。

## 用户体验设计

### 展示位置

工具状态展示在当前 assistant 消息内部，位于正文 markdown 内容之前。

### 默认展开

工具状态默认展开，不做摘要折叠模式。

### 多次工具调用

如果同一条 assistant 回复内连续调用多个工具，前端应按工具调用开始的时间顺序展示多个步骤，并在工具完成后继续保留这些步骤。

### 单个步骤展示内容

每个工具步骤只展示：

- 工具名
- 当前状态文案
- 最近一次状态更新时间或等价的本地顺序标记
- 失败时的错误信息

不展示工具入参和返回结果。

### 状态流

前端需要支持以下状态值：

- `started`
- `running`
- `succeeded`
- `failed`

对应的中文展示文案建议为：

- `started -> 开始调用`
- `running -> 调用中`
- `succeeded -> 成功`
- `failed -> 失败`

## 后端设计

### 流协议

继续沿用现有 `/api/chat/stream` SSE 通道，不新增独立轮询接口或第二条订阅链路。

工具生命周期事件继续使用 `eventType = "tool"`，但事件体需要新增一个稳定的关联字段 `toolCallId`。

建议事件结构如下：

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

`content` 和 `metadata` 可以为了兼容性继续保留，但这次功能不依赖它们来做展示。如果需要，失败时可以让 `content` 携带一段简短错误信息，其余状态保持为空字符串。

### 生命周期规则

对于每一次工具调用：

1. 执行前发出 `started`
2. 执行中在有明确钩子的情况下发出 `running`
3. 成功结束时发出 `succeeded`
4. 调用异常或无法完成时发出 `failed`

最小要求是：至少发出 `started` 和一个终态事件。  
如果当前底层框架没有稳定的中间执行钩子，`running` 可以暂时省略。

### 关联规则

`toolCallId` 是这次改造里的关键字段。

没有它，前端只能把同一个工具调用的多次状态变化误当成多条记录。加入 `toolCallId` 后，前端才能把同一次调用聚合为一个步骤并持续更新其状态。

后端必须为每次工具调用生成唯一的 `toolCallId`，并确保该调用生命周期内的所有事件都带上相同的 `toolCallId`。

### 失败处理

如果工具调用失败，后端需要：

- 为该 `toolCallId` 发出 `failed` 事件
- 提供一段适合前端直接展示的简短错误信息
- 不因为单次工具步骤失败而导致前端整条消息渲染崩溃

模型流本身失败仍走现有的 `error` 事件路径，与工具失败分开处理。

## 前端设计

### 消息状态模型

当前 assistant 消息中的 `toolEvents[]` 需要升级为 `toolCalls[]`。

`toolCalls[]` 中的每一项代表一次完整的工具调用生命周期，而不是一次原始事件。

建议的数据结构如下：

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

### 状态更新规则

当前端收到一条工具事件时：

1. 先用 `toolCallId` 查找是否已有对应步骤
2. 如果没有，则创建新的步骤并追加到当前 assistant 消息的 `toolCalls[]`
3. 如果已存在，则更新该步骤的 `status`、`updatedAt`
4. 当状态为 `failed` 时，更新 `errorMessage`
5. 列表顺序以首次出现为准，不因后续更新而重排

### 渲染规则

每条 assistant 消息渲染时：

- 先渲染工具步骤列表
- 再渲染 assistant markdown 正文

每个工具步骤渲染时：

- 展示工具名
- 展示中文状态文案
- 当状态为 `failed` 时展示错误信息

工具步骤默认始终可见，这次不做折叠能力。

### 非法事件处理

如果前端收到缺少关键关联字段的工具事件，应直接忽略，不要因为单个坏事件破坏整条消息的渲染。

## 预期改动文件

这次功能预计主要涉及：

- `src/main/java/com/tc/ai/api/ChatStreamEvent.java`
- `src/main/java/com/tc/ai/service/ChatToolEventBridge.java`
- `src/main/java/com/tc/ai/service/SpringAiAgentChatService.java`
- `frontend/src/lib/stream-chat.js`
- `frontend/src/App.vue`
- `frontend/src/styles.css`（如需补样式）

## 手工验证方式

这次功能不写自动化测试，验收以页面手工验证为主。

最小验证流程如下：

1. 提问一个会触发 `calculate` 的问题。
2. 确认当前 assistant 消息下立刻出现一个新的工具步骤，状态为“开始调用”。
3. 确认该步骤会随着后续事件更新状态，而不是重复生成多张无关联卡片。
4. 确认工具成功后，该步骤状态变为“成功”，且仍保留在消息中。
5. 再提问一个会触发 `getCurrentTime` 的问题。
6. 确认不同回复中的工具步骤分别归属到各自的 assistant 消息下。
7. 模拟或触发一次工具失败，确认页面展示“失败”和简短错误信息。
8. 确认 assistant 正文仍然可以正常流式输出，不会被工具状态区域覆盖或打断。

## 范围说明

这次功能只覆盖当前聊天页面和现有 SSE 流架构，不包含以下扩展能力：

- 工具调用历史持久化
- 跨消息的全局工具时间线
- 面向开发调试的独立观测台
