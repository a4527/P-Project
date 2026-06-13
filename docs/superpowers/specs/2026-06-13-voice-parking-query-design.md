# 음성 주차 현황 질의 기능 설계 (웹)

- 작성일: 2026-06-13
- 상태: 승인 대기
- 관련 영역: `springboot/` (백엔드 + 웹 UI)

## 1. 배경 / 목표

사용자가 **음성으로 "AI공학관 지하1층 빈자리 있어?"** 처럼 물으면:
1. 브라우저가 음성을 텍스트로 바꾸고(STT),
2. 서버가 현재 점유 현황을 근거로 LLM(Gemini)에게 자연스러운 답변을 생성시키고,
3. 브라우저가 그 답변을 음성으로 읽어준다(TTS).

이미 시스템은 점유 상태를 숫자로 알고 있으므로(YOLO → `/status`), LLM은 **질문 의도 해석 + 자연어 답변 생성**만 담당한다.

### 비목표
- 모바일 앱(별도 후속 작업) — 이 음성 기능을 웹에서 먼저 완성해 재사용 기반으로 둔다.
- VLM(이미지 직접 판단) — 점유는 YOLO 숫자를 그대로 쓴다.
- 클라우드 STT/TTS — 브라우저 Web Speech API(무료)로 충분.

## 2. 핵심 결정 (확정)

- **AI 역할:** LLM이 우리 점유 숫자로 답변 생성 (NLU + 자연어 응답). 숫자 판단은 LLM이 하지 않음.
- **STT/TTS:** 브라우저 **Web Speech API** (무료, 한국어, 서버 부담 없음).
- **LLM 제공자/모델:** **Google Gemini, `gemini-2.5-flash-lite`** (경량·빠름·무료 한도 넉넉). 라이브 검증 완료(한국어 정상 답변, 입력 129 / 출력 23 토큰).
- **대상 해석(A안):** 서버가 **모든 건물·주차장의 점유 요약**(웹에서 저장한 이름 포함)을 LLM에 통째로 주고, LLM이 질문 속 건물/파티션 이름을 해석해 답한다. 캠퍼스 규모가 작아 토큰 부담 없음.
- **파티션 지정:** 사용자가 특정 주차장(파티션)을 지목하면 그 주차장 기준, 아니면 건물 단위로 답.

## 3. 아키텍처 / 데이터 흐름

```
[브라우저]                               [Spring Boot]                 [Gemini]
 마이크 → Web Speech STT
   │ 질문 텍스트
   └──► POST /api/voice/ask ──► 현재 점유 요약 수집(건물·주차장 이름+빈자리)
                                  │ 질문 + 요약(프롬프트)
                                  └──────────────────────────────► gemini-2.5-flash-lite
        { answer } ◄────────────  답변 텍스트 추출 ◄───────────────  답변
 Web Speech TTS → 스피커
```

음성↔텍스트는 전부 브라우저(무료). 서버는 "질문 + 점유요약 → 답변텍스트" 한 가지 책임. Gemini 키는 서버에만.

## 4. 컴포넌트 (각자 단일 책임)

- **브라우저 음성 모듈** (`app.js`): 🎤 버튼 → STT로 질문 텍스트화 → `/api/voice/ask` 호출 → 답변 텍스트 표시 + TTS 출력. 미지원 브라우저면 버튼 비활성+안내.
- **VoiceController** (`POST /api/voice/ask`): `{question}` 수신 → 서비스 호출 → `{answer}` 반환. 빈 질문은 400.
- **VoiceAnswerService**: `CampusMapService`/`ParkingStatusService`로 현재 점유 요약 텍스트 조립 → 프롬프트 구성 → `GeminiClient` 호출 → 답변 텍스트 반환. 점유 데이터 없음/LLM 실패 시 안전한 기본 문장.
- **GeminiClient**: `geminiWebClient`(WebClient)로 Gemini `generateContent` 호출, 응답에서 답변 텍스트 추출. 키는 환경변수.

## 5. API 형식

| 메서드·경로 | 입력 | 출력 |
|---|---|---|
| `POST /api/voice/ask` | `{ "question": "AI공학관 지하1층 빈자리 있어?" }` | `{ "answer": "현재 AI공학관 지하1층은 4자리 비어 있어요." }` |

- 공개(비로그인 허용). 기존 `anyRequest().permitAll()`에 해당, 별도 보안 규칙 불필요.

### Gemini 호출 형식 (확정/검증됨)
- URL: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key={KEY}`
- 요청: `{ "contents":[{"parts":[{"text": <프롬프트>}]}], "generationConfig":{"maxOutputTokens":100,"temperature":0.3} }`
- 응답: `candidates[0].content.parts[0].text`

### 점유 요약 텍스트 (서버 조립 예)
```
건물: AI공학관
 - 주차장 "지하 1층"(gachon_ai_1): 총 41칸, 빈자리 4
 - 주차장 "정문"(gachon_ai_2): 총 36칸, 빈자리 0
건물: 학생회관
 - 주차장 "옥외"(student_1): 총 20칸, 빈자리 12
```
각 주차장의 **표시 이름은 웹에서 저장한 `ParkingLot.name`**, 보조로 `partitionKey` 포함. 점유 수는 `ParkingStatusService`의 summary.

### 프롬프트 요지
- 시스템 지시: "주차 안내 도우미. 아래 현황만 근거로 한국어 한두 문장으로 자연스럽게 답하라. 현황에 없는 곳은 모른다고 답하라. 숫자를 지어내지 마라."
- 입력: 점유 요약 + 사용자 질문.

## 6. 설정 · 보안

- `application.properties`: `smartparking.gemini.api-key=${SMARTPARKING_GEMINI_API_KEY:}` (값은 `~/.zshrc`/환경변수, git 미커밋 — 네이버 검색 키와 동일 패턴).
- `WebClientConfig`: `geminiWebClient` 빈 추가(baseUrl `https://generativelanguage.googleapis.com`).
- `run.sh`: `SMARTPARKING_GEMINI_API_KEY`도 `~/.zshrc`에서 자동 로드.
- 키는 서버에만. 프론트는 우리 `/api/voice/ask`만 호출.

## 7. 에러 처리

- 점유 데이터 없음(FastAPI 미가동) → "지금 점유 정보를 가져올 수 없어요" 류 답변.
- Gemini 실패/타임아웃 → 서버가 안전한 기본 문장 반환("잠시 후 다시 시도해 주세요").
- STT 실패/미지원(브라우저) → 버튼 비활성 또는 "잘 못 들었어요, 다시 말씀해 주세요".
- 현황과 무관한 질문 → 프롬프트 지시에 따라 LLM이 "해당 정보가 없어요"로 답.

## 8. 테스트 전략

| 대상 | 방식 |
|---|---|
| 점유 요약 생성 | 단위 테스트 — 건물·주차장·빈자리 텍스트 조립 검증(Gemini 호출 없이) |
| VoiceAnswerService | GeminiClient를 목 처리해 "요약+질문 전달 → 답변 반환" 흐름 검증 |
| `POST /api/voice/ask` | MockMvc — 요청/응답 형식, 빈 질문 400 |
| Gemini 실제 호출 | 자동 테스트 미포함(외부·비결정) → 키 넣고 수동 라이브 검증(curl) |
| 프론트 STT/TTS | 수동 — 마이크 버튼→질문→음성 답변 |

## 9. 신규/수정 파일 (예정)

- 신규: `controller/VoiceController.java`, `service/VoiceAnswerService.java`, `service/GeminiClient.java`, `dto/VoiceAskRequest.java`, `dto/VoiceAskResponse.java`
- 수정: `config/WebClientConfig.java`(geminiWebClient), `resources/application.properties`, `resources/static/app.js`(🎤+STT/TTS), `resources/static/index.html`(버튼), `resources/static/app.css`, `run.sh`

## 10. 리스크 / 열린 사항

- Web Speech API는 **브라우저/기기별 품질 편차**(특히 한국어 인식). Chrome 권장.
- Gemini 무료 티어 **분당/일일 요청 제한** — 개발·데모엔 충분, 대량 트래픽 시 유료/대안 필요.
- LLM 응답 비결정성 → 자동 테스트는 흐름만, 품질은 수동 확인.
- 점유 정확도는 슬롯 정의(맵 빌더) 여부에 의존 — 음성 기능과 독립.
