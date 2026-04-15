const API_BASE = import.meta.env.VITE_API_BASE || '';

export async function streamChat({ message, file, onToken, onError }) {
  const formData = new FormData();
  formData.append('message', message);
  if (file) {
    formData.append('file', file);
  }

  const response = await fetch(`${API_BASE}/api/chat/stream`, {
    method: 'POST',
    body: formData,
    headers: {
      Accept: 'text/event-stream',
    },
  });

  if (!response.ok || !response.body) {
    throw new Error(`Chat request failed with status ${response.status}.`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split('\n\n');
    buffer = chunks.pop() || '';

    for (const chunk of chunks) {
      const event = parseSseChunk(chunk);
      if (!event?.data) {
        continue;
      }

      if (event.data.eventType === 'token') {
        onToken?.(event.data.content || '');
      } else if (event.data.eventType === 'error') {
        onError?.(event.data.content || 'The server returned an error.');
      }
    }
  }
}

function parseSseChunk(chunk) {
  const lines = chunk.split('\n');
  const event = { event: '', data: null };

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event.event = line.slice(6).trim();
    }

    if (line.startsWith('data:')) {
      const payload = line.slice(5).trim();
      event.data = JSON.parse(payload);
    }
  }

  return event;
}
