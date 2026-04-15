package com.tc.ai.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tc.ai.controller.AgentChatController;
import com.tc.ai.service.AgentChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

@WebMvcTest(controllers = AgentChatController.class)
class AgentChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentChatService agentChatService;

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void chatEndpointReturnsContent() throws Exception {
        MockMultipartFile messagePart = new MockMultipartFile(
                "message",
                "",
                MediaType.TEXT_PLAIN_VALUE,
                "hello".getBytes()
        );

        given(agentChatService.complete(eq("hello"), any())).willReturn("world");

        mockMvc.perform(multipart("/api/chat").file(messagePart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("world"));

        verify(agentChatService).complete(eq("hello"), any());
    }

    @Test
    void streamEndpointReturnsServerSentEvents() throws Exception {
        MockMultipartFile messagePart = new MockMultipartFile(
                "message",
                "",
                MediaType.TEXT_PLAIN_VALUE,
                "hello".getBytes()
        );

        given(agentChatService.stream(eq("hello"), any())).willReturn(Flux.just(
                ChatStreamEvent.token("hel"),
                ChatStreamEvent.token("lo"),
                ChatStreamEvent.done()
        ));

        mockMvc.perform(multipart("/api/chat/stream").file(messagePart))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"eventType\":\"token\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"eventType\":\"done\"")));
    }

    @Test
    void chatEndpointAcceptsSingleFileUpload() throws Exception {
        MockMultipartFile messagePart = new MockMultipartFile(
                "message",
                "",
                MediaType.TEXT_PLAIN_VALUE,
                "summarize".getBytes()
        );
        MockMultipartFile filePart = new MockMultipartFile(
                "file",
                "note.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "hello file".getBytes()
        );

        given(agentChatService.complete(eq("summarize"), any())).willReturn("summary");

        mockMvc.perform(multipart("/api/chat").file(messagePart).file(filePart))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("summary"));

        verify(agentChatService).complete(eq("summarize"), any());
    }
}
