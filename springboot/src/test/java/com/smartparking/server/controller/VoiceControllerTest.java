package com.smartparking.server.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.smartparking.server.service.VoiceAnswerService;
import com.smartparking.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VoiceControllerTest {

    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private VoiceAnswerService voiceAnswerService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void askReturnsAnswer() throws Exception {
        when(voiceAnswerService.ask(anyString())).thenReturn("현재 4자리 비어 있어요.");
        mockMvc.perform(post("/api/voice/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"빈자리 있어?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("현재 4자리 비어 있어요."));
    }

    @Test
    void blankQuestionReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/voice/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
