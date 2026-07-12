% 스마트 주차 시스템 개발 보고서
% 스마트 주차 프로젝트 팀
% 2026-06-13

# 1. 문서 개요

본 보고서는 YOLO 영상분석 기반 스마트 주차 점유 감지 시스템의 전체 구조와, 이번 개발 주기에서 수행한 기능 고도화(지도 기반 주차장 동적 등록, 장소명 검색, 음성 질의응답, 로컬 LLM fallback)의 내용을 처음부터 끝까지 정리한 것이다.

- 대상 독자: 프로젝트 팀원, 지도 교수/평가자
- 범위: 백엔드(Spring Boot, FastAPI), 웹 프런트엔드, 외부 연동(네이버, Gemini, Ollama)
- 작성일: 2026-06-13

# 2. 프로젝트 개요

본 프로젝트는 드론/CCTV 영상에서 차량을 탐지하여 주차 구획(슬롯)의 점유 여부를 자동으로 판단하고, 그 현황을 웹에서 실시간으로 제공하는 스마트 주차 시스템이다.

핵심 가치는 다음과 같다.

- 영상 한 대로 다수의 주차 구획 점유를 자동 계산(수동 센서 불필요)
- 사용자가 지도에서 직접 주차장을 등록·관리(코드 수정 없이 운영 가능)
- 지도·검색·음성 등 다양한 방식으로 빈자리 확인

# 3. 추진 배경 및 목적

## 3.1 기존 한계

- 건물·주차장 정보가 소스코드에 하드코딩되어 있어 새 주차장 추가 시 코드 수정·재배포가 필요했다.
- 데이터가 인메모리 DB에 저장되어 서버를 재시작하면 모든 등록 정보가 사라졌다.
- 사용 방식이 웹 화면 조회에 한정되어 접근성이 낮았다.

## 3.2 목적

- 지도에서 위치를 지정해 주차장을 동적으로 등록·삭제할 수 있도록 개선한다.
- 등록 데이터를 영속 저장하여 재시작 후에도 유지한다.
- 장소명 검색, 음성 질의 등 사용 편의 기능을 추가한다.
- 웹에서 지도·검색·음성 질의·사용자별 기능을 통합 제공한다.

# 4. 시스템 아키텍처

## 4.1 구성요소

| 구성요소 | 역할 | 주요 기술 |
|---|---|---|
| FastAPI 추론 서버 | 영상에서 차량 탐지, 슬롯 점유 계산, 현황 제공 | Python, YOLO(ultralytics), OpenCV |
| Spring Boot 서버 | 캠퍼스/건물/주차장 관리 API, 웹 UI, 인증, 음성·검색 프록시 | Java 17, Spring Boot 4, JPA, PostgreSQL, Spring Security |
| 웹 프런트엔드 | 지도/현황 조회, 주차장 등록, 검색, 음성 | HTML/CSS/JS, 네이버 지도, Web Speech API |
| 외부 연동 | 지도 표시, 장소 검색, 자연어 답변 정리 | 네이버 지도/지역검색, Google Gemini, Ollama |

## 4.2 데이터 흐름

1. FastAPI가 Spring Boot의 분석 manifest를 주기적으로 조회(약 5초)하여 새 영상을 자동 인식한다.
2. YOLO가 영상 프레임에서 차량을 탐지하고, PostgreSQL의 `slot_layout_json`에서 전달된 슬롯 영역과 대조하여 점유 여부를 계산한다.
3. 계산 결과는 FastAPI `/status`로 제공되고, Spring Boot가 이를 주기적으로 폴링하여 캐시한다.
4. 웹은 Spring Boot API를 통해 캠퍼스·건물·주차장 현황과 실시간 점유 상태를 받아 표시한다.
5. 음성 질의 시, Spring Boot가 현재 점유 현황에서 정확한 수치 답변 초안을 만들고 Gemini 또는 Ollama에 전달해 자연어 표현을 정리해 반환한다.

## 4.3 데이터 모델(엔티티)

```
Campus 1 ── N Building 1 ── N ParkingLot
User 1 ── N SavedParkingLocation
User 1 ── N ParkingAlertRule
User 1 ── N InAppNotification
```

- **Campus**: 캠퍼스(지도 중심 좌표, 기본 줌)
- **Building**: 건물(이름, mapKey, 위도/경도). 지도 마커 단위
- **ParkingLot**: 주차장(이름, partitionKey, 슬롯 레이아웃). 점유 계산 단위
- **User / SavedParkingLocation / ParkingAlertRule / InAppNotification**: 로그인 사용자와 그 부가 데이터(주차 위치, 알림 규칙, 웹 알림)

식별자 규칙

- `mapKey`: 건물 식별자(파일·마커 그룹 기준). 동적 생성 시 `bldg-xxxxxxxx` 형태
- `partitionKey`: 주차장 식별자(영상·이미지·슬롯·점유 상태 연결 기준). `{mapKey}_{번호}` 형태
- 영상과 이미지는 MinIO에 저장하고, 슬롯 JSON은 PostgreSQL의 `parking_lots.slot_layout_json`에 저장한다.

# 5. 구현 기능 상세

## 5.1 주차 점유 감지 (기반 기능)

- FastAPI가 영상을 읽고, 보조 프레임 조건과 최소 분석 주기(기본 10초)를 모두 만족할 때 YOLO 추론을 수행한다.
- 탐지된 차량 중심점이 슬롯 영역 내부에 있으면 해당 슬롯을 점유로 판정한다.
- 슬롯 정의(`parking_lots.slot_layout_json`)는 웹 슬롯 편집기에서 배경 사진 위에 주차 칸을 배치해 저장한다.
- 결과는 주차장(partitionKey)별 총 칸 수, 빈자리 수, 장애인석 빈자리 수로 요약된다.

## 5.2 지도 기반 주차장 동적 등록 (신규)

기능 흐름

1. 웹 지도에서 빈 영역을 클릭한다.
2. 건물 이름을 입력하면 클릭 좌표로 **건물(Building)** 이 생성되고 지도에 마커가 즉시 표시된다.
3. 생성한 건물에 **영상을 업로드**하여 주차장(ParkingLot)을 추가한다. partitionKey가 자동 생성되고 영상은 MinIO에 저장된다.
4. FastAPI가 manifest를 갱신하고 새 영상을 자동 인식하여 점유 계산을 시작한다.
5. 잘못 등록한 경우 건물/주차장을 삭제할 수 있으며, 연관 레코드와 파일이 함께 정리된다.

주요 개선

- 하드코딩되어 있던 건물 시드를 제거하고, 모든 건물·주차장을 런타임에 등록하도록 변경
- PostgreSQL과 MinIO를 Docker Compose로 통합하여 실행 환경을 일관되게 구성함
- 영상 업로드를 위해 업로드 용량 한도를 상향(2GB)하고 디스크 스트리밍 저장 적용

## 5.3 장소명 검색 (신규)

기능 흐름

1. 검색창에 "가천대"와 같은 장소명을 입력한다.
2. 서버가 네이버 지역검색 API(서버 프록시)를 호출하여 좌표를 받아 지도를 해당 위치로 이동한다.
3. 이동 후 사용자가 지도를 클릭하여 주차장을 등록할 수 있다.

비고

- 비밀키는 서버에만 보관(프록시 방식)하며, 무료 호출 한도 내에서 운용한다.

## 5.4 음성 질의응답 (신규)

기능 흐름

1. 사용자가 음성으로 "AI공학관 빈자리 있어?"와 같이 묻는다.
2. 브라우저 Web Speech API가 음성을 텍스트로 변환한다.
3. 서버가 현재 전체 주차 현황을 기반으로 정확한 수치 답변 초안을 생성한다.
4. Gemini API 키가 있으면 Gemini가, 없으면 Ollama 로컬 LLM(`qwen2.5:1.5b`)이 초안의 말투를 자연스럽게 다듬는다.
5. 답변을 화면에 표시하고 TTS로 음성 출력한다.

설계 요점

- 점유 수치는 시스템이 이미 계산하므로 LLM은 "질문 표현 처리 + 자연어 답변 정리"만 담당한다.
- Gemini API 키가 없으면 로컬 Ollama 모델 `qwen2.5:1.5b`를 사용한다.
- 음성 입출력은 브라우저 기본 기능을 사용하여 서버 비용을 최소화한다.

## 5.5 사용자/인증 및 부가 기능

- 인증: 회원가입/로그인, JWT 토큰 기반 인증
- 내 주차위치 저장: 슬롯 선택 후 현재 주차 위치 저장·해제(백엔드 API 구비)
- 빈자리 알림: 임계값 기반 알림 규칙 등록, 조건 충족 시 웹 알림 생성
- 조회·음성은 비로그인 허용, 주차위치·알림은 로그인 필요

# 6. API 명세 (요약)

## 6.1 FastAPI

| 메서드·경로 | 설명 |
|---|---|
| GET /status | 주차장(partitionKey)별 점유 현황 |

## 6.2 Spring Boot — 조회/설정

| 메서드·경로 | 설명 |
|---|---|
| GET /api/campus/map | 캠퍼스 + 건물 + 주차장 요약(지도 마커용) |
| GET /api/campus/buildings/{id} | 건물 상세 + 주차장 + 슬롯 정보 |
| GET /api/parking/status | 원시 점유 캐시 |
| GET /api/ui/config | 네이버 지도 클라이언트 ID 등 |

## 6.3 Spring Boot — 신규(이번 개발)

| 메서드·경로 | 설명 | 인증 |
|---|---|---|
| POST /api/buildings | 지도 좌표로 건물 생성 | 필요 |
| DELETE /api/buildings/{id} | 건물 및 하위 주차장·파일 삭제 | 필요 |
| POST /api/buildings/{id}/parking-lots | 주차장 추가(영상 업로드) | 필요 |
| DELETE /api/parking-lots/{id} | 주차장 삭제(파일 정리) | 필요 |
| GET /api/geo/search?query= | 장소명 검색(네이버 지역검색 프록시) | 불필요 |
| POST /api/voice/ask | 음성 질의 → 자연어 답변 | 불필요 |

## 6.4 Spring Boot — 인증/사용자

| 메서드·경로 | 설명 |
|---|---|
| POST /auth/register, POST /auth/login, GET /auth/me | 회원가입/로그인/세션 확인 |
| GET·POST·DELETE /api/me/parking-location(/current) | 내 주차위치 조회/저장/해제 |
| GET·POST·PUT·DELETE /api/me/alert-rules | 빈자리 알림 규칙 관리 |
| GET·PATCH /api/me/notifications | 웹 알림 조회/읽음 처리 |

# 7. 기술 스택

| 구분 | 기술 |
|---|---|
| 백엔드(API) | Java 17, Spring Boot 4, Spring Data JPA, Spring Security, WebFlux(WebClient), PostgreSQL |
| 추론 서버 | Python, FastAPI, Ultralytics YOLO, OpenCV, uvicorn |
| 웹 | HTML/CSS/JavaScript, 네이버 지도 v3, Web Speech API |
| 로컬 LLM | Ollama, qwen2.5:1.5b |
| 외부 API | 네이버 지도/지역검색, Google Gemini(선택) |
| 인증 | JWT |

# 8. 개발 환경 및 실행 방법

1. 프로젝트 루트에서 `docker compose up --build` 실행
   - 웹: `http://localhost:8080/`
   - Ollama가 `qwen2.5:1.5b` 모델을 받아 LLM fallback으로 사용
2. 검색 기능은 백엔드에 네이버 API 키(환경변수)가 설정되어 있어야 동작

# 9. 보안 및 키 관리

- 모든 외부 API 키(네이버 지도/지역검색, Gemini)와 JWT 비밀키는 **환경변수**로 분리 관리하며 소스에 포함하지 않는다.
- 검색·음성은 서버 프록시 방식으로 비밀키를 클라이언트에 노출하지 않는다.
- PostgreSQL 데이터와 MinIO 객체는 Docker volume으로 관리한다.

# 10. 테스트 및 검증

- 백엔드 단위/통합 테스트(JUnit, MockMvc) 작성 및 전체 통과
- 신규 API(건물·주차장 등록/삭제, 음성, 검색)는 서버 기동 후 실제 호출로 동작 검증
- 데이터 영속성(재시작 후 등록 데이터 유지) 검증
- 웹에서 지도, 검색, 음성 질의, 사용자별 기능을 수동 동작 확인

# 11. 진행 현황 요약

| 기능 | 웹 |
|---|---|
| 주차 현황 지도 표시 | 완료 |
| 지도 기반 주차장 등록·삭제 | 완료 |
| 장소명 검색 | 완료 |
| 음성 질의응답 | 완료 |
| 즐겨찾기 | 완료 |
| 내 주차위치 저장 | 완료 |
| 빈자리 알림 | 완료 |

# 12. 향후 계획 (로드맵)

- 1단계: 로컬 LLM 답변 품질 개선 및 프롬프트 튜닝
- 2단계: PostgreSQL 운영 설정 강화
- 3단계: 빈자리 알림 전달 채널 확장
- 운영 단계: PostgreSQL 운영 최적화, 푸시 알림(FCM) 도입 검토

# 13. 리스크 및 고려사항

- 점유 정확도는 슬롯 정의(웹 편집) 품질과 영상 구도에 의존한다.
- 음성 인식 품질은 단말/브라우저에 따라 편차가 있다.
- 외부 API는 무료 한도가 있으므로 대규모 트래픽 시 로컬 LLM fallback 또는 운영 비용 검토가 필요하다.
- LLM 답변은 비결정적이므로 현황 데이터에 근거한 응답으로 제한한다.

# 14. 부록

## 14.1 브랜치 구성

- 백엔드/웹: `feat/map-pin-registration`

## 14.2 산출물/문서

- 현재 백엔드 구조: `docs/backend-directories.md`
- 기능별 전체 흐름: `docs/feature-flows.md`
- 통합 실행 설정: `docker-compose.yml`
