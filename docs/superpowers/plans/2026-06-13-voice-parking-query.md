# 음성 주차 현황 질의 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 웹에서 음성으로 "AI공학관 지하1층 빈자리 있어?"라고 물으면, 브라우저 STT→서버가 점유 요약을 Gemini로 보내 자연어 답변 생성→브라우저 TTS로 음성 응답한다.

**Architecture:** 음성↔텍스트는 브라우저 Web Speech API(무료)가 담당. 서버는 `POST /api/voice/ask`에서 현재 점유 요약(`CampusMapService.getCampusMap()` 재사용)을 만들어 질문과 함께 `gemini-2.5-flash-lite`에 보내고 답변 텍스트만 돌려준다. Gemini 키는 서버 환경변수.

**Tech Stack:** Spring Boot 4 (Web, WebFlux WebClient, Validation), Gemini API(generativelanguage REST), JUnit5 + MockMvc + Mockito, 바닐라 JS Web Speech API.

**참고 설계 문서:** `docs/superpowers/specs/2026-06-13-voice-parking-query-design.md`

---

## File Structure

**신규 (백엔드):**
- `springboot/src/main/java/com/smartparking/server/dto/VoiceAskRequest.java` — `{question}`
- `springboot/src/main/java/com/smartparking/server/dto/VoiceAskResponse.java` — `{answer}`
- `springboot/src/main/java/com/smartparking/server/dto/GeminiResponse.java` — Gemini 응답 매핑
- `springboot/src/main/java/com/smartparking/server/service/GeminiClient.java` — Gemini generateContent 호출
- `springboot/src/main/java/com/smartparking/server/service/VoiceAnswerService.java` — 점유 요약 조립 + 프롬프트 + Gemini 호출
- `springboot/src/main/java/com/smartparking/server/controller/VoiceController.java` — `POST /api/voice/ask`
- `springboot/src/test/java/com/smartparking/server/service/VoiceAnswerServiceTest.java`
- `springboot/src/test/java/com/smartparking/server/controller/VoiceControllerTest.java`

**수정 (백엔드):**
- `springboot/src/main/resources/application.properties` — gemini api-key
- `springboot/src/main/java/com/smartparking/server/config/WebClientConfig.java` — `geminiWebClient` 빈

**수정 (프론트/기타):**
- `springboot/src/main/resources/static/index.html` — 🎤 버튼 + 출력 영역
- `springboot/src/main/resources/static/app.js` — STT/TTS + 호출
- `springboot/src/main/resources/static/app.css` — 버튼/출력 스타일
- `run.sh` — `SMARTPARKING_GEMINI_API_KEY` 자동 로드

---

## Task 1: Gemini 설정 (properties + WebClient 빈 + run.sh)

**Files:**
- Modify: `springboot/src/main/resources/application.properties`
- Modify: `springboot/src/main/java/com/smartparking/server/config/WebClientConfig.java`
- Modify: `run.sh`

- [ ] **Step 1: application.properties에 키 설정 추가**

`application.properties`의 `smartparking.naver-search.client-secret=...` 줄 바로 다음에 추가:
```properties
smartparking.gemini.api-key=${SMARTPARKING_GEMINI_API_KEY:}
```

- [ ] **Step 2: geminiWebClient 빈 추가**

`config/WebClientConfig.java`의 `naverSearchWebClient(...)` 빈 메서드 다음(클래스 닫는 `}` 앞)에 추가:
```java
    @Bean
    public WebClient geminiWebClient() {
        return WebClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }
```

- [ ] **Step 3: run.sh에 Gemini 키 자동 로드 추가**

`run.sh`에서 `load_from_zshrc SMARTPARKING_NAVER_SEARCH_CLIENT_SECRET` 줄 다음에 추가:
```bash
load_from_zshrc SMARTPARKING_GEMINI_API_KEY
```

- [ ] **Step 4: 컴파일 확인**

Run:
```bash
cd /Users/leehnsong/P-Project/springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH ./gradlew compileJava -q && echo OK
```
Expected: `OK` (에러 없음).

- [ ] **Step 5: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/application.properties \
        springboot/src/main/java/com/smartparking/server/config/WebClientConfig.java run.sh
git commit -m "chore: Gemini API 설정(키 환경변수, geminiWebClient, run.sh 로드)"
```

---

## Task 2: GeminiClient (응답 DTO + 호출)

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/dto/GeminiResponse.java`
- Create: `springboot/src/main/java/com/smartparking/server/service/GeminiClient.java`

이 태스크는 외부 호출이라 단위 테스트 대신 컴파일로 검증하고, 실제 응답은 Task 6의 라이브 검증에서 확인한다.

- [ ] **Step 1: Gemini 응답 매핑 DTO 작성**

`dto/GeminiResponse.java`:
```java
package com.smartparking.server.dto;

import java.util.List;
import lombok.Data;

/** Gemini generateContent 응답에서 필요한 부분만 매핑. (알 수 없는 필드는 무시됨) */
@Data
public class GeminiResponse {

    private List<Candidate> candidates;

    @Data
    public static class Candidate {
        private Content content;
    }

    @Data
    public static class Content {
        private List<Part> parts;
    }

    @Data
    public static class Part {
        private String text;
    }
}
```

- [ ] **Step 2: GeminiClient 작성**

`service/GeminiClient.java`:
```java
package com.smartparking.server.service;

import com.smartparking.server.dto.GeminiResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class GeminiClient {

    private static final String MODEL = "gemini-2.5-flash-lite";

    private final WebClient geminiWebClient;
    private final String apiKey;

    public GeminiClient(WebClient geminiWebClient,
                        @Value("${smartparking.gemini.api-key:}") String apiKey) {
        this.geminiWebClient = geminiWebClient;
        this.apiKey = apiKey;
    }

    /** 프롬프트를 보내고 생성된 텍스트를 반환. 실패 시 null. */
    public String generate(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API 키가 설정되지 않음");
            return null;
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("maxOutputTokens", 100, "temperature", 0.3));

        try {
            GeminiResponse response = geminiWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/" + MODEL + ":generateContent")
                            .queryParam("key", apiKey)
                            .build())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(GeminiResponse.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();

            if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
                return null;
            }
            GeminiResponse.Content content = response.getCandidates().get(0).getContent();
            if (content == null || content.getParts() == null || content.getParts().isEmpty()) {
                return null;
            }
            String text = content.getParts().get(0).getText();
            return text == null ? null : text.trim();
        } catch (Exception e) {
            log.warn("Gemini 호출 실패: {}", e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run:
```bash
cd /Users/leehnsong/P-Project/springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH ./gradlew compileJava -q && echo OK
```
Expected: `OK`.

- [ ] **Step 4: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/dto/GeminiResponse.java \
        springboot/src/main/java/com/smartparking/server/service/GeminiClient.java
git commit -m "feat: Gemini generateContent 호출 클라이언트"
```

---

## Task 3: VoiceAnswerService (점유 요약 + 프롬프트 + 답변) — TDD

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/service/VoiceAnswerService.java`
- Test: `springboot/src/test/java/com/smartparking/server/service/VoiceAnswerServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (순수 요약 조립 + 폴백)**

`src/test/java/com/smartparking/server/service/VoiceAnswerServiceTest.java`:
```java
package com.smartparking.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.dto.ParkingLotView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceAnswerServiceTest {

    @Mock
    private com.smartparking.server.service.CampusMapService campusMapService;
    @Mock
    private GeminiClient geminiClient;
    @InjectMocks
    private VoiceAnswerService voiceAnswerService;

    private CampusMapResponse sampleMap() {
        ParkingLotView lot = new ParkingLotView();
        lot.setName("지하 1층");
        lot.setPartitionKey("gachon_ai_1");
        lot.setSummary(new ParkingLotView.Summary("AVAILABLE", 41, 4, 0, null));

        CampusMapResponse.BuildingView building = new CampusMapResponse.BuildingView();
        building.setName("AI공학관");
        building.setParkingLots(List.of(lot));

        CampusMapResponse map = new CampusMapResponse();
        map.setBuildings(List.of(building));
        return map;
    }

    @Test
    void buildSummaryIncludesBuildingLotNameAndCounts() {
        String summary = VoiceAnswerService.buildSummary(sampleMap());
        assertThat(summary).contains("AI공학관");
        assertThat(summary).contains("지하 1층");
        assertThat(summary).contains("gachon_ai_1");
        assertThat(summary).contains("41");
        assertThat(summary).contains("4");
    }

    @Test
    void askReturnsGeminiAnswer() {
        when(campusMapService.getCampusMap()).thenReturn(sampleMap());
        when(geminiClient.generate(anyString())).thenReturn("현재 지하 1층은 4자리 비어 있어요.");

        String answer = voiceAnswerService.ask("AI공학관 지하1층 빈자리 있어?");

        assertThat(answer).isEqualTo("현재 지하 1층은 4자리 비어 있어요.");
    }

    @Test
    void askReturnsFallbackWhenGeminiFails() {
        when(campusMapService.getCampusMap()).thenReturn(sampleMap());
        when(geminiClient.generate(anyString())).thenReturn(null);

        String answer = voiceAnswerService.ask("빈자리 있어?");

        assertThat(answer).isNotBlank();
        assertThat(answer).contains("다시");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run:
```bash
cd /Users/leehnsong/P-Project/springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH ./gradlew test --tests "*VoiceAnswerServiceTest" -q
```
Expected: 컴파일 실패 또는 FAIL (`VoiceAnswerService` 없음).

- [ ] **Step 3: VoiceAnswerService 구현**

`service/VoiceAnswerService.java`:
```java
package com.smartparking.server.service;

import com.smartparking.server.dto.CampusMapResponse;
import com.smartparking.server.dto.ParkingLotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VoiceAnswerService {

    private static final String FALLBACK = "지금은 답변을 가져올 수 없어요. 잠시 후 다시 시도해 주세요.";

    private final CampusMapService campusMapService;
    private final GeminiClient geminiClient;

    public String ask(String question) {
        if (question == null || question.isBlank()) {
            return "무엇을 도와드릴까요?";
        }
        CampusMapResponse map = campusMapService.getCampusMap();
        String summary = buildSummary(map);
        String prompt = buildPrompt(summary, question);
        String answer = geminiClient.generate(prompt);
        return (answer == null || answer.isBlank()) ? FALLBACK : answer;
    }

    static String buildSummary(CampusMapResponse map) {
        StringBuilder sb = new StringBuilder();
        if (map == null || map.getBuildings() == null || map.getBuildings().isEmpty()) {
            return "(등록된 주차장이 없습니다)";
        }
        for (CampusMapResponse.BuildingView building : map.getBuildings()) {
            sb.append("건물: ").append(building.getName()).append("\n");
            if (building.getParkingLots() == null || building.getParkingLots().isEmpty()) {
                sb.append(" - (주차장 없음)\n");
                continue;
            }
            for (ParkingLotView lot : building.getParkingLots()) {
                ParkingLotView.Summary s = lot.getSummary();
                if (s != null && s.getTotalSlots() != null && s.getTotalSlots() > 0) {
                    sb.append(" - 주차장 \"").append(lot.getName()).append("\"(")
                            .append(lot.getPartitionKey()).append("): 총 ")
                            .append(s.getTotalSlots()).append("칸, 빈자리 ")
                            .append(s.getAvailableSlots() == null ? 0 : s.getAvailableSlots())
                            .append("\n");
                } else {
                    sb.append(" - 주차장 \"").append(lot.getName()).append("\"(")
                            .append(lot.getPartitionKey()).append("): 점유 정보 없음\n");
                }
            }
        }
        return sb.toString().trim();
    }

    static String buildPrompt(String summary, String question) {
        return "너는 주차 안내 도우미다. 아래 [현황]만 근거로 한국어 한두 문장으로 자연스럽게 답하라. "
                + "[현황]에 없는 곳을 물으면 모른다고 답하라. 숫자를 지어내지 마라.\n\n"
                + "[현황]\n" + summary + "\n\n[질문] " + question;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/leehnsong/P-Project/springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH ./gradlew test --tests "*VoiceAnswerServiceTest" -q
```
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/service/VoiceAnswerService.java \
        springboot/src/test/java/com/smartparking/server/service/VoiceAnswerServiceTest.java
git commit -m "feat: 음성 답변 서비스(점유 요약→프롬프트→Gemini, 폴백) + 테스트"
```

---

## Task 4: VoiceController + DTO — TDD

**Files:**
- Create: `springboot/src/main/java/com/smartparking/server/dto/VoiceAskRequest.java`
- Create: `springboot/src/main/java/com/smartparking/server/dto/VoiceAskResponse.java`
- Create: `springboot/src/main/java/com/smartparking/server/controller/VoiceController.java`
- Test: `springboot/src/test/java/com/smartparking/server/controller/VoiceControllerTest.java`

- [ ] **Step 1: DTO 두 개 작성**

`dto/VoiceAskRequest.java`:
```java
package com.smartparking.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VoiceAskRequest {
    @NotBlank
    private String question;
}
```

`dto/VoiceAskResponse.java`:
```java
package com.smartparking.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VoiceAskResponse {
    private String answer;
}
```

- [ ] **Step 2: 컨트롤러 작성**

`controller/VoiceController.java`:
```java
package com.smartparking.server.controller;

import com.smartparking.server.dto.VoiceAskRequest;
import com.smartparking.server.dto.VoiceAskResponse;
import com.smartparking.server.service.VoiceAnswerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceAnswerService voiceAnswerService;

    @PostMapping("/ask")
    public ResponseEntity<VoiceAskResponse> ask(@Valid @RequestBody VoiceAskRequest request) {
        return ResponseEntity.ok(new VoiceAskResponse(voiceAnswerService.ask(request.getQuestion())));
    }
}
```

- [ ] **Step 3: 실패하는 컨트롤러 테스트 작성**

`src/test/java/com/smartparking/server/controller/VoiceControllerTest.java`:
```java
package com.smartparking.server.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.smartparking.server.service.VoiceAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
        "smartparking.asset-root=${java.io.tmpdir}/sp-test-assets",
        "spring.datasource.url=jdbc:h2:mem:sptestvoice;DB_CLOSE_DELAY=-1"
})
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
```

> 참고: 이 프로젝트는 Spring Boot 4라 `@AutoConfigureMockMvc`가 없어 `webAppContextSetup`+`springSecurity()`로 MockMvc를 만든다(기존 `BuildingControllerTest`와 동일). 서비스는 `@MockitoBean`으로 대체해 실제 Gemini 호출을 막는다. `/api/voice/**`는 인증 불필요(공개)라 `@WithMockUser` 없이 통과해야 한다.

- [ ] **Step 4: 테스트 통과 확인**

Run:
```bash
cd /Users/leehnsong/P-Project/springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH ./gradlew test --tests "*VoiceControllerTest" -q
```
Expected: PASS (2 tests). 만약 `@MockitoBean` import가 이 버전에서 다르면 `org.springframework.boot.test.mock.mockito.MockBean`으로 시도한다(둘 중 존재하는 것 사용).

- [ ] **Step 5: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/java/com/smartparking/server/dto/VoiceAskRequest.java \
        springboot/src/main/java/com/smartparking/server/dto/VoiceAskResponse.java \
        springboot/src/main/java/com/smartparking/server/controller/VoiceController.java \
        springboot/src/test/java/com/smartparking/server/controller/VoiceControllerTest.java
git commit -m "feat: 음성 질의 API(POST /api/voice/ask) + 테스트"
```

---

## Task 5: 프론트엔드 — 마이크 버튼 + STT/TTS

**Files:**
- Modify: `springboot/src/main/resources/static/index.html`
- Modify: `springboot/src/main/resources/static/app.js`
- Modify: `springboot/src/main/resources/static/app.css`

- [ ] **Step 1: index.html에 버튼/출력 영역 추가**

`index.html`에서 `<div class="map-search">` 블록 바로 다음(닫는 `</div>` 다음 줄)에 추가:
```html
                <div class="voice-box">
                    <button id="voice-button" type="button">🎤 음성으로 묻기</button>
                    <span id="voice-output" class="voice-output"></span>
                </div>
```

- [ ] **Step 2: app.js에 음성 바인딩 추가**

`app.js`의 `document.addEventListener("DOMContentLoaded", () => {` 블록에서 `bindAuthActions();` 다음 줄에 추가:
```javascript
    bindVoice();
```

- [ ] **Step 3: app.js 하단에 음성 함수들 추가**

`app.js` 맨 아래에 추가:
```javascript
function getSpeechRecognition() {
    return window.SpeechRecognition || window.webkitSpeechRecognition || null;
}

function bindVoice() {
    const button = document.getElementById("voice-button");
    const output = document.getElementById("voice-output");
    if (!button) {
        return;
    }
    const Recognition = getSpeechRecognition();
    if (!Recognition || !("speechSynthesis" in window)) {
        button.disabled = true;
        button.textContent = "🎤 음성 미지원 브라우저";
        return;
    }
    button.addEventListener("click", () => startVoiceQuery(button, output, Recognition));
}

function startVoiceQuery(button, output, Recognition) {
    const recognition = new Recognition();
    recognition.lang = "ko-KR";
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;

    button.disabled = true;
    if (output) {
        output.textContent = "🎙️ 듣는 중...";
    }

    recognition.onresult = async (event) => {
        const question = event.results[0][0].transcript;
        if (output) {
            output.textContent = `질문: ${question}`;
        }
        try {
            const result = await apiRequest("/api/voice/ask", {
                method: "POST",
                body: JSON.stringify({ question }),
            });
            const answer = result?.answer ?? "답변을 받지 못했어요.";
            if (output) {
                output.textContent = `Q: ${question} / A: ${answer}`;
            }
            speak(answer);
        } catch (error) {
            if (output) {
                output.textContent = `오류: ${error.message}`;
            }
        } finally {
            button.disabled = false;
        }
    };

    recognition.onerror = () => {
        if (output) {
            output.textContent = "잘 못 들었어요. 다시 시도해 주세요.";
        }
        button.disabled = false;
    };

    recognition.onend = () => {
        button.disabled = false;
    };

    recognition.start();
}

function speak(text) {
    if (!("speechSynthesis" in window)) {
        return;
    }
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "ko-KR";
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
}
```

- [ ] **Step 4: app.css에 스타일 추가**

`app.css` 맨 아래에 추가:
```css
.voice-box { display: flex; align-items: center; gap: 10px; margin: 0 18px 10px; }
.voice-box button { padding: 8px 16px; border: none; border-radius: 999px; background: var(--accent); color: #fff; font-size: 0.85rem; font-weight: 600; cursor: pointer; }
.voice-box button:disabled { opacity: 0.5; cursor: not-allowed; }
.voice-output { font-size: 0.85rem; color: var(--muted); }
```

- [ ] **Step 5: JS 문법 확인**

Run:
```bash
node --check /Users/leehnsong/P-Project/springboot/src/main/resources/static/app.js && echo "JS OK"
```
Expected: `JS OK`.

- [ ] **Step 6: 커밋**

```bash
cd /Users/leehnsong/P-Project
git add springboot/src/main/resources/static/index.html \
        springboot/src/main/resources/static/app.js \
        springboot/src/main/resources/static/app.css
git commit -m "feat(web): 음성으로 주차 현황 질의(STT/TTS) UI"
```

---

## Task 6: 라이브 검증 + 전체 회귀

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 백엔드 전체 테스트 회귀**

Run:
```bash
cd /Users/leehnsong/P-Project/springboot && JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH ./gradlew test
```
Expected: BUILD SUCCESSFUL, 모든 테스트 통과.

- [ ] **Step 2: 서버 기동 후 음성 API 라이브 검증**

Run (Gemini 키 포함 기동 → 더미 건물/주차장 없이도 응답 형식 확인):
```bash
cd /Users/leehnsong/P-Project/springboot
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:$PATH
export SMARTPARKING_GEMINI_API_KEY="$(grep -E '^export SMARTPARKING_GEMINI_API_KEY=' ~/.zshrc | tail -1 | sed -E 's/^[^=]+=//; s/"//g')"
lsof -ti:8080 | xargs kill -9 2>/dev/null
./gradlew bootRun > /tmp/sb_voice.log 2>&1 &
for i in $(seq 1 90); do grep -q "Started ServerApplication" /tmp/sb_voice.log 2>/dev/null && break; sleep 1; done
curl -s -X POST http://localhost:8080/api/voice/ask -H "Content-Type: application/json" \
  -d '{"question":"빈자리 있는 주차장 알려줘"}' | python3 -m json.tool
lsof -ti:8080 | xargs kill -9 2>/dev/null
```
Expected: `{"answer": "..."}` 형태의 한국어 답변(현황이 비어 있으면 "등록된 주차장이 없다"는 취지). 빈 `answer`나 폴백이면 로그 `/tmp/sb_voice.log`에서 Gemini 오류 확인.

- [ ] **Step 3: 브라우저 수동 검증 (마이크)**

Run:
```bash
cd /Users/leehnsong/P-Project && ./run.sh
```
Chrome에서 http://localhost:8080/ → 🎤 버튼 클릭 → 마이크 권한 허용 → "AI공학관 빈자리 있어?" 말하기 → 화면에 Q/A 표시 + 음성으로 답변이 나오는지 확인. (건물/주차장을 미리 등록해두면 더 정확) 확인 후 `Ctrl+C`.

Expected: 음성 질문 → 음성 답변 동작.

- [ ] **Step 4: (변경 없음) 커밋 불필요**

검증만 수행. 코드 변경이 있었다면 해당 태스크로 돌아가 수정.

---

## Self-Review 메모 (작성자 확인 완료)

- **Spec 커버리지:** STT/TTS(T5), `/api/voice/ask`(T4), 점유 요약+프롬프트+Gemini(T3), GeminiClient(T2), 설정·키·run.sh(T1), 라이브 검증(T6) — 설계 각 절 대응.
- **타입 일관성:** `VoiceAnswerService.ask/buildSummary/buildPrompt`, `GeminiClient.generate(String)`, DTO `VoiceAskRequest.question`/`VoiceAskResponse.answer`, `GeminiResponse.candidates[0].content.parts[0].text` — 태스크 간 시그니처 일치. `ParkingLotView.Summary` 게터(getTotalSlots/getAvailableSlots)와 `CampusMapResponse.BuildingView`(getName/getParkingLots)는 실제 코드 확인됨.
- **플레이스홀더:** 없음(모든 코드 단계 실제 코드 포함).
- **알려진 한계:** 보안 변경 불필요(`/api/voice/**`는 `anyRequest().permitAll()`). Web Speech API 브라우저 편차/마이크 권한은 수동 검증으로만 확인. `@MockitoBean`이 버전에 따라 `@MockBean`일 수 있어 Task4 Step4에 대안 명시.
