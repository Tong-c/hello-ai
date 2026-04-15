<script setup>
import { computed, ref } from 'vue';
import { streamChat } from './lib/stream-chat';

const messages = ref([]);
const messageInput = ref('');
const selectedFile = ref(null);
const isSending = ref(false);
const errorMessage = ref('');

const hasMessages = computed(() => messages.value.length > 0);

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

  const assistantMessage = {
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    attachmentName: null,
  };
  messages.value.push(assistantMessage);
  isSending.value = true;

  try {
    await streamChat({
      message: trimmedMessage,
      file: selectedFile.value,
      onToken(content) {
        assistantMessage.content += content;
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
          <p>{{ message.content || (message.role === 'assistant' && isSending ? '...' : '') }}</p>
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
