<script setup>
import { computed, reactive, ref } from 'vue';
import { streamChat } from './lib/stream-chat';

const messages = ref([]);
const messageInput = ref('');
const selectedFile = ref(null);
const isSending = ref(false);
const errorMessage = ref('');

const hasMessages = computed(() => messages.value.length > 0);
const TOOL_STATUS_LABELS = {
  started: '开始调用',
  running: '调用中',
  succeeded: '成功',
  failed: '失败',
};
const TOOL_STATUS_ORDER = ['started', 'running', 'succeeded', 'failed'];

function escapeHtml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function renderInlineMarkdown(text) {
  return escapeHtml(text)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
}

function getToolStatusLabel(status) {
  return TOOL_STATUS_LABELS[status] || '调用中';
}

function getToolStepState(toolEvent, stepStatus) {
  const currentIndex = TOOL_STATUS_ORDER.indexOf(toolEvent.status);
  const stepIndex = TOOL_STATUS_ORDER.indexOf(stepStatus);

  if (toolEvent.status === 'failed') {
    if (stepStatus === 'failed') {
      return 'active';
    }
    return toolEvent.transitions.includes(stepStatus) ? 'done' : 'pending';
  }

  if (toolEvent.transitions.includes(stepStatus) && stepIndex < currentIndex) {
    return 'done';
  }

  if (toolEvent.status === stepStatus) {
    return 'active';
  }

  return 'pending';
}

function upsertToolEvent(assistantMessage, event) {
  if (!event.toolCallId) {
    return;
  }

  const toolCallId = event.toolCallId;
  let toolEvent = assistantMessage.toolEvents.find((item) => item.toolCallId === toolCallId);

  if (!toolEvent) {
    toolEvent = {
      toolCallId,
      toolName: event.toolName || 'tool',
      status: event.status || 'started',
      errorContent: '',
      transitions: event.status ? [event.status] : [],
    };
    if (toolEvent.status === 'failed') {
      toolEvent.errorContent = event.content || '工具调用失败';
    }
    assistantMessage.toolEvents.push(toolEvent);
    return;
  }

  if (event.toolName) {
    toolEvent.toolName = event.toolName;
  }

  if (event.status) {
    toolEvent.status = event.status;
    const lastStatus = toolEvent.transitions[toolEvent.transitions.length - 1];
    if (lastStatus !== event.status) {
      toolEvent.transitions.push(event.status);
    }
  }

  if (toolEvent.status === 'failed') {
    toolEvent.errorContent = event.content || toolEvent.errorContent || '';
  } else if (toolEvent.status === 'succeeded') {
    toolEvent.errorContent = '';
  }
}

function renderMarkdown(content) {
  if (!content) {
    return '';
  }

  const lines = content.replace(/\r\n/g, '\n').split('\n');
  const html = [];
  let inCodeBlock = false;
  let codeLines = [];
  let listType = null;
  let paragraphLines = [];

  function flushParagraph() {
    if (!paragraphLines.length) {
      return;
    }
    html.push(`<p>${renderInlineMarkdown(paragraphLines.join(' '))}</p>`);
    paragraphLines = [];
  }

  function flushList() {
    if (listType) {
      html.push(`</${listType}>`);
      listType = null;
    }
  }

  function flushCodeBlock() {
    if (!inCodeBlock) {
      return;
    }
    html.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`);
    inCodeBlock = false;
    codeLines = [];
  }

  for (const line of lines) {
    if (line.startsWith('```')) {
      flushParagraph();
      flushList();
      if (inCodeBlock) {
        flushCodeBlock();
      } else {
        inCodeBlock = true;
      }
      continue;
    }

    if (inCodeBlock) {
      codeLines.push(line);
      continue;
    }

    const trimmed = line.trim();
    if (!trimmed) {
      flushParagraph();
      flushList();
      continue;
    }

    const headingMatch = trimmed.match(/^(#{1,3})\s+(.*)$/);
    if (headingMatch) {
      flushParagraph();
      flushList();
      const level = headingMatch[1].length;
      html.push(`<h${level}>${renderInlineMarkdown(headingMatch[2])}</h${level}>`);
      continue;
    }

    const quoteMatch = trimmed.match(/^>\s?(.*)$/);
    if (quoteMatch) {
      flushParagraph();
      flushList();
      html.push(`<blockquote>${renderInlineMarkdown(quoteMatch[1])}</blockquote>`);
      continue;
    }

    const unorderedMatch = trimmed.match(/^[-*]\s+(.*)$/);
    if (unorderedMatch) {
      flushParagraph();
      if (listType !== 'ul') {
        flushList();
        listType = 'ul';
        html.push('<ul>');
      }
      html.push(`<li>${renderInlineMarkdown(unorderedMatch[1])}</li>`);
      continue;
    }

    const orderedMatch = trimmed.match(/^\d+\.\s+(.*)$/);
    if (orderedMatch) {
      flushParagraph();
      if (listType !== 'ol') {
        flushList();
        listType = 'ol';
        html.push('<ol>');
      }
      html.push(`<li>${renderInlineMarkdown(orderedMatch[1])}</li>`);
      continue;
    }

    paragraphLines.push(trimmed);
  }

  flushParagraph();
  flushList();
  flushCodeBlock();
  return html.join('');
}

function onFileChange(event) {
  const [file] = event.target.files || [];
  selectedFile.value = file || null;
}

async function submitMessage() {
  if (isSending.value) {
    return;
  }

  const trimmedMessage = messageInput.value.trim();
  if (!trimmedMessage && !selectedFile.value) {
    errorMessage.value = 'Enter a message or attach a file.';
    return;
  }

  errorMessage.value = '';
  const attachmentName = selectedFile.value?.name || null;

  messages.value.push({
    id: crypto.randomUUID(),
    role: 'user',
    content: trimmedMessage || '(Uploaded file only)',
    attachmentName,
  });

  const assistantMessage = reactive({
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    attachmentName: null,
    toolEvents: [],
  });
  messages.value.push(assistantMessage);
  isSending.value = true;

  try {
    await streamChat({
      message: trimmedMessage,
      file: selectedFile.value,
      onToken(content) {
        assistantMessage.content += content;
      },
      onTool(event) {
        upsertToolEvent(assistantMessage, event);
      },
      onError(content) {
        errorMessage.value = content;
      },
    });
  } catch (error) {
    errorMessage.value = error.message || 'Request failed.';
    if (!assistantMessage.content) {
      assistantMessage.content = 'The agent could not return a response.';
    }
  } finally {
    isSending.value = false;
    messageInput.value = '';
    selectedFile.value = null;
    const fileInput = document.getElementById('file-input');
    if (fileInput) {
      fileInput.value = '';
    }
  }
}
</script>

<template>
  <main class="shell">
    <section class="panel">
      <div class="hero">
        <p class="eyebrow">Personal Agent Console</p>
        <p class="subtitle">Vue + Vite frontend wired to the Spring AI backend over streaming chat.</p>
      </div>

      <section class="transcript" :class="{ empty: !hasMessages }">
        <div v-if="!hasMessages" class="empty-state">
          <p>Ask a question or upload a file to start the first chat run.</p>
        </div>

        <article v-for="message in messages" :key="message.id" class="message" :data-role="message.role">
          <header>
            <span>{{ message.role === 'user' ? 'You' : 'Agent' }}</span>
            <span v-if="message.attachmentName" class="attachment">{{ message.attachmentName }}</span>
          </header>
          <p v-if="message.role === 'user'">{{ message.content || '' }}</p>
          <template v-else>
            <div v-if="message.toolEvents?.length" class="tool-events">
              <div
                v-for="toolEvent in message.toolEvents"
                :key="toolEvent.toolCallId"
                class="tool-event"
              >
                <div class="tool-event-header">
                  <span class="tool-badge">{{ toolEvent.toolName }}</span>
                  <span class="tool-status">{{ getToolStatusLabel(toolEvent.status) }}</span>
                </div>
                <div class="tool-steps">
                  <div
                    v-for="stepStatus in TOOL_STATUS_ORDER"
                    :key="stepStatus"
                    class="tool-step"
                    :data-step-state="getToolStepState(toolEvent, stepStatus)"
                  >
                    <span class="tool-step-dot" />
                    <span class="tool-step-label">{{ getToolStatusLabel(stepStatus) }}</span>
                  </div>
                </div>
                <p v-if="toolEvent.status === 'failed' && toolEvent.errorContent" class="tool-error">
                  {{ toolEvent.errorContent }}
                </p>
              </div>
            </div>
            <div
              class="markdown-content"
              v-html="renderMarkdown(message.content || (isSending ? '...' : ''))"
            />
          </template>
        </article>
      </section>

      <form class="composer" @submit.prevent="submitMessage">
        <label class="field">
          <span>Message</span>
          <textarea
            v-model="messageInput"
            rows="4"
            placeholder="Ask the agent something useful."
            :disabled="isSending"
          />
        </label>

        <div class="controls">
          <label class="file-picker" for="file-input">
            <span>{{ selectedFile ? selectedFile.name : 'Attach one file' }}</span>
          </label>
          <input id="file-input" type="file" @change="onFileChange" :disabled="isSending" />

          <button class="send" type="submit" :disabled="isSending">
            {{ isSending ? 'Streaming...' : 'Send' }}
          </button>
        </div>

        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
      </form>
    </section>
  </main>
</template>
