# 졸업 프로젝트 담당 내용 중심 정리

## 프로젝트 개요

이 프로젝트는 주차장 영상을 기반으로 실시간 주차 가능 여부를 분석하고, 사용자가 웹에서 주차 현황을 확인할 수 있도록 만든 스마트 주차 시스템이다.

전체 시스템은 크게 세 부분으로 구성된다.

```text
웹
  -> Spring Boot 백엔드
      -> FastAPI 기반 주차 상태 분석 서버
      -> Gemini API 또는 Ollama 로컬 LLM
      -> DB
```

FastAPI 서버는 주차장 영상을 분석해 주차 슬롯별 점유 상태를 계산한다. Spring Boot 백엔드는 이 분석 결과를 REST API로 받아와 웹에 제공하고, 사용자 인증, 사용자별 데이터 관리, 음성 질의 응답, 외부 API 연동을 담당한다.

## 내가 담당한 역할

이 프로젝트에서 나는 Spring Boot 백엔드를 담당했다.

주요 담당 내용은 다음과 같다.

- FastAPI 기반 주차 상태 분석 서버와 Spring Boot 백엔드 연동
- Gemini API와 Ollama 로컬 LLM을 활용한 주차 현황 기반 자연어 답변 기능 구현
- JWT 인증 및 Spring Security 기반 접근 제어 구현
- JPA 기반 사용자 관리 기능 구현
- 외부 서버 연동 시 타임아웃, 예외 처리, 기본 응답 제공 로직 적용
- 외부 API 실패, 서버 미응답, 인증 실패 등 다양한 실패 시나리오 테스트

## 시스템에서 Spring Boot 백엔드의 역할

Spring Boot 백엔드는 클라이언트와 외부 서버 사이의 중심 서버 역할을 한다.

웹은 FastAPI, Gemini API, Ollama를 직접 호출하지 않고 Spring Boot API만 호출한다. Spring Boot는 내부에서 FastAPI 주차 분석 서버, Gemini API 또는 Ollama, DB를 연동해 클라이언트가 필요한 형태의 응답으로 가공한다.

이 구조를 통해 클라이언트는 복잡한 외부 연동 로직을 알 필요 없이 백엔드 API만 사용하면 되고, 외부 서버 장애나 응답 지연 같은 문제도 Spring Boot에서 일관되게 처리할 수 있다.

## FastAPI 주차 상태 분석 서버 연동

FastAPI 서버는 YOLO 기반 영상 분석을 통해 주차 슬롯별 상태를 계산한다. 각 슬롯은 `available` 또는 `occupied` 상태로 관리되며, 전체 주차면 수와 빈자리 수 같은 요약 정보도 함께 제공한다.

Spring Boot 백엔드는 FastAPI의 주차 상태 API를 REST 방식으로 호출한다. 클라이언트 요청이 들어올 때마다 FastAPI를 직접 호출하는 방식이 아니라, Spring Boot가 주기적으로 FastAPI 서버에서 상태를 가져와 캐싱하고 클라이언트에는 캐시된 데이터를 제공한다.

이렇게 구성한 이유는 다음과 같다.

- YOLO 분석 서버의 응답 속도에 클라이언트 응답이 직접 영향을 받지 않도록 하기 위해
- FastAPI 서버가 일시적으로 불안정해도 Spring Boot가 완전히 실패하지 않도록 하기 위해
- 여러 클라이언트 요청이 몰려도 분석 서버 호출 부담을 줄이기 위해

주차 현황 조회 흐름은 다음과 같다.

```text
FastAPI 서버
  -> 영상 분석
  -> 슬롯별 occupied / available 계산
  -> /status API 제공

Spring Boot 백엔드
  -> FastAPI /status 호출
  -> 응답 데이터 캐싱
  -> DB의 건물/주차장 정보와 결합
  -> 웹에 주차 현황 API 제공
```

Spring Boot에서는 FastAPI 응답을 그대로 전달하지 않고, DB에 저장된 건물, 주차장, 사용자 관련 데이터와 결합해 클라이언트가 사용하기 쉬운 응답 형태로 가공했다.

## Gemini API / Ollama 로컬 LLM 연동

프로젝트에는 사용자가 주차 현황을 자연어로 질문할 수 있는 기능이 있다. 예를 들어 사용자가 “AI공학관 빈자리 있어?”처럼 질문하면, 현재 주차 현황을 기반으로 자연스러운 한국어 답변을 제공한다.

이 기능에서 Spring Boot 백엔드는 Gemini API와 REST 방식으로 연동된다. Gemini API 키가 없거나 호출에 실패하면 Ollama 로컬 LLM(`qwen2.5:1.5b`)으로 같은 프롬프트를 전달한다.

처리 흐름은 다음과 같다.

```text
사용자 질문
  -> Spring Boot API 요청
  -> 현재 주차장별 빈자리 현황 조회
  -> 정확한 수치 기반 답변 초안 생성
  -> Gemini API 또는 Ollama 로컬 LLM 호출
  -> 자연어 답변 반환
```

LLM에는 사용자의 질문만 전달하지 않았다. 먼저 Spring Boot에서 현재 주차 현황 데이터를 요약하고 정확한 수치 기반 답변 초안을 만든 뒤, 그 초안을 말투만 자연스럽게 다듬도록 전달했다.

이렇게 한 이유는 LLM이 실제 시스템에 없는 주차장 정보나 임의의 숫자를 만들어내지 않도록 하기 위해서다. 즉, AI가 답변을 생성하더라도 정확한 수치와 근거 데이터는 Spring Boot가 계산한 현재 주차 현황으로 제한했다.

## JWT 인증과 Spring Security

사용자별 기능을 제공하기 위해 JWT 기반 인증을 구현했다.

로그인에 성공하면 Spring Boot 백엔드가 JWT를 발급한다. 이후 클라이언트는 인증이 필요한 API를 호출할 때 `Authorization` 헤더에 Bearer 토큰을 포함한다.

```text
Authorization: Bearer {JWT}
```

Spring Security에서는 API 성격에 따라 접근 권한을 분리했다.

공개 API:

- 로그인
- 회원가입
- 캠퍼스 지도 조회
- 주차 현황 조회
- 웹 정적 리소스 조회

인증 필요 API:

- 현재 사용자 정보 조회
- 내 주차 위치 저장/조회/삭제
- 사용자별 알림 설정
- 알림함 조회
- 건물 및 주차장 등록/삭제

JWT 인증 필터는 요청에 포함된 토큰을 검증하고, 유효한 토큰인 경우 인증 정보를 Spring Security Context에 저장한다. 이를 통해 컨트롤러와 서비스 계층에서 현재 로그인한 사용자를 기준으로 데이터를 처리할 수 있게 했다.

## JPA 기반 사용자 관리

사용자 정보와 사용자별 기능은 JPA를 기반으로 구현했다.

주요 관리 대상은 다음과 같다.

- 사용자 계정
- 내 주차 위치
- 빈자리 알림 조건
- 웹 알림
- 건물 및 주차장 정보

JPA Repository를 사용해 DB 접근 로직을 분리했고, 서비스 계층에서 비즈니스 로직을 처리하도록 구성했다.

예를 들어 내 주차 위치 기능에서는 로그인한 사용자 기준으로 주차 위치를 저장하고, 다시 조회하거나 삭제할 수 있도록 했다. 빈자리 알림 기능에서는 사용자가 특정 주차장에 대해 원하는 최소 빈자리 수를 설정하면, 백엔드가 현재 주차 상태를 확인해 조건을 만족할 때 알림을 생성하도록 구성했다.

## 외부 서버 연동 시 장애 대응

이 프로젝트에서 특히 신경 쓴 부분은 외부 서버 연동 과정의 안정성이다.

Spring Boot 백엔드는 FastAPI 서버, Gemini API, 네이버 API처럼 외부 의존성이 있는 기능을 호출한다. 외부 서버는 항상 정상적으로 응답한다는 보장이 없기 때문에, 다음과 같은 장애 상황을 고려했다.

- FastAPI 서버가 꺼져 있는 경우
- FastAPI 서버 응답이 지연되는 경우
- Gemini API 키가 없거나 잘못된 경우
- Gemini API 또는 Ollama 호출이 실패하는 경우
- 네이버 검색 API 호출이 실패하는 경우
- 외부 API 응답 형식이 비어 있거나 예상과 다른 경우
- 인증 토큰이 없거나 만료된 경우

이를 위해 외부 API 호출에는 타임아웃을 적용했다. 응답이 일정 시간 안에 오지 않으면 무한정 대기하지 않고 실패로 처리하도록 했다.

또한 예외가 발생해도 서버 전체 요청이 중단되지 않도록 각 연동 지점에 예외 처리를 적용했다. 실패 시에는 기능 성격에 맞게 기본 응답을 제공했다.

예시는 다음과 같다.

- FastAPI 주차 상태 조회 실패 시 기존 캐시를 유지하거나 데이터 없음 응답 제공
- Gemini API 호출 실패 시 Ollama 로컬 LLM 사용, Ollama도 실패하면 규칙 기반 정확 답변 반환
- 네이버 검색 실패 시 빈 검색 결과 반환
- 인증 실패 시 `401 Unauthorized` 반환

이러한 처리를 통해 외부 서버 하나가 실패하더라도 전체 백엔드가 함께 장애로 이어지지 않도록 했다.

## 테스트한 실패 시나리오

단순히 정상 동작만 확인하지 않고, 실패 상황에서도 의도한 응답이 나오는지 확인했다.

주요 테스트 시나리오는 다음과 같다.

- FastAPI 서버가 실행되지 않은 상태에서 주차 현황 조회
- FastAPI 응답이 없는 상황에서 Spring Boot 서버가 계속 동작하는지 확인
- Gemini API 키가 없는 상태에서 Ollama fallback으로 음성 질의 요청
- Gemini API와 Ollama 호출 실패 시 규칙 기반 답변 반환 확인
- 로그인하지 않은 사용자가 인증 필요 API 호출
- 잘못된 JWT 또는 만료된 JWT로 API 호출
- 존재하지 않는 건물이나 주차장 ID로 요청
- 빈 데이터 상태에서 지도/주차장 조회 요청

이 과정을 통해 외부 연동 실패, 인증 실패, 데이터 없음 상황에서도 백엔드가 예측 가능한 방식으로 응답하도록 개선했다.

## 프로젝트에서 배운 점

이 프로젝트를 통해 단순히 API를 구현하는 것뿐 아니라, 여러 서버와 외부 API를 연결하는 백엔드에서 안정성이 중요하다는 점을 배웠다.

특히 FastAPI, Gemini API, Ollama처럼 외부 시스템에 의존하는 기능은 정상 응답만 가정해서 구현하면 실제 사용 환경에서 문제가 발생할 수 있다. 그래서 타임아웃, 예외 처리, 기본 응답, 캐싱 같은 방어 로직이 필요하다는 것을 경험했다.

또한 JWT와 Spring Security를 적용하면서 공개 API와 인증 API를 분리하는 방식, 사용자별 데이터를 안전하게 관리하는 방식, JPA를 활용해 도메인 데이터를 관리하는 방식을 익힐 수 있었다.

## 면접에서 강조할 핵심 포인트

면접에서는 다음 흐름으로 설명하면 좋다.

1. 프로젝트는 YOLO 영상 분석 기반 스마트 주차 시스템이다.
2. 나는 Spring Boot 백엔드를 담당했다.
3. Spring Boot는 클라이언트, FastAPI 분석 서버, Gemini API/Ollama, DB를 연결하는 중심 서버 역할을 했다.
4. FastAPI 주차 상태 결과를 REST API로 가져와 캐싱하고, DB 데이터와 결합해 웹에 제공했다.
5. 정확한 수치는 Spring Boot가 계산하고, Gemini API 또는 Ollama에는 답변 표현 정리만 맡겼다.
6. JWT와 Spring Security로 사용자 인증과 API 접근 제어를 구현했다.
7. JPA 기반으로 사용자, 내 주차 위치, 알림, 주차장 데이터를 관리했다.
8. 외부 서버 연동에는 타임아웃, 예외 처리, 기본 응답을 적용해 장애 상황을 고려했다.
9. 여러 실패 시나리오를 테스트해 백엔드가 안정적으로 응답하는지 확인했다.

## 짧은 설명 예시

이 프로젝트는 주차장 영상을 분석해 실시간 주차 가능 여부를 제공하는 스마트 주차 시스템입니다. 저는 Spring Boot 백엔드를 담당했고, FastAPI 기반 YOLO 분석 서버와 Gemini API/Ollama 로컬 LLM을 REST API로 연동했습니다. FastAPI에서 분석한 주차 슬롯 상태를 주기적으로 가져와 캐싱하고, DB의 건물 및 주차장 정보와 결합해 웹에 제공했습니다. 또한 JWT 인증과 Spring Security를 적용해 사용자별 기능을 보호했고, JPA 기반으로 사용자 관리, 내 주차 위치, 빈자리 알림 기능을 구현했습니다. 외부 서버 연동 과정에서는 타임아웃과 예외 처리, 기본 응답 로직을 적용해 FastAPI나 LLM 호출 실패 상황에서도 백엔드가 안정적으로 동작하도록 설계했습니다.

---

# 기술 면접 예상 질문과 답변

아래 답변은 현재 저장소의 구현을 기준으로 작성했다. 면접에서는 구현한 부분과
향후 개선 아이디어를 구분해서 말하는 것이 좋다.

## 1. 프로젝트·아키텍처

### Q1. 프로젝트를 한 문장으로 설명해 주세요.

주차장 영상을 FastAPI의 YOLO 서버로 분석해 슬롯별 점유 상태를 계산하고,
Spring Boot가 그 결과를 DB의 건물·주차장 정보와 결합해 웹과 음성 질의 API로
제공하는 스마트 주차 시스템입니다.

### Q2. 전체 요청 흐름은 어떻게 됩니까?

브라우저의 `app.js`가 Spring Boot Controller API를 호출합니다. Controller는
Service에 업무를 위임하고, Service는 JPA Repository, MinIO StorageService,
FastAPI 또는 외부 API를 사용합니다. FastAPI의 영상 분석 결과는 Spring Boot가
주기적으로 받아 메모리에 캐시하고, 웹 요청에는 캐시와 DB 정보를 조합해 응답합니다.

```text
index.html/app.js
  -> Spring Boot Controller
  -> Service
  -> PostgreSQL / MinIO / FastAPI / Gemini / Ollama
```

### Q3. 왜 Spring Boot와 FastAPI를 분리했나요?

Spring Boot는 인증, DB, 파일 메타데이터, 웹 API에 적합하고, YOLO·PyTorch·OpenCV
기반 영상 추론은 Python 생태계가 적합합니다. 분석 서버를 분리하면 모델 의존성과
CPU 사용량이 웹/API 서버에 직접 영향을 주는 것을 줄일 수 있고, 각 서버를
독립적으로 교체하거나 확장할 수 있습니다.

### Q4. 모든 기능을 Spring Boot에 넣으면 안 되나요?

가능하지만 Ultralytics YOLO와 PyTorch를 Java 프로세스에 직접 통합해야 하므로
배포와 의존성 관리가 복잡해집니다. 현재 구조에서는 Spring Boot가 분석 대상과
결과 제공을 담당하고, FastAPI가 영상 추론만 담당하는 경계가 명확합니다.

### Q5. Spring Boot가 중심 서버인 이유는 무엇인가요?

브라우저가 DB, MinIO, YOLO, LLM을 직접 호출하지 않도록 단일 API 진입점을
제공하기 때문입니다. 인증, 권한, 응답 형식, 장애 처리를 서버에서 통일하고
외부 서비스 주소나 인증 키를 브라우저에 노출하지 않을 수 있습니다.

### Q6. Docker Compose에서는 서비스가 어떻게 연결되나요?

`springboot`, `fastapi`, `postgres`, `minio`, `ollama`가 같은 Compose 네트워크에
있습니다. 컨테이너 내부에서는 `localhost`가 아니라 서비스 이름으로 통신합니다.
예를 들어 Spring Boot는 PostgreSQL에 `postgres:5432`, FastAPI에
`fastapi:8000`, MinIO에 `minio:9000`으로 연결합니다.

### Q7. 서비스별 책임을 설명해 주세요.

- Spring Boot: 웹/API, 인증, DB, MinIO 연결, 외부 API 프록시
- FastAPI: 영상 읽기, YOLO 차량 탐지, 슬롯 점유 계산, `/status` 제공
- PostgreSQL: 사용자·캠퍼스·건물·주차장·알림·asset 메타데이터 저장
- MinIO: 업로드 영상과 이미지의 실제 바이너리 저장
- Ollama/Gemini: 이미 계산된 주차 사실을 자연어로 다듬는 역할

### Q8. Controller와 Service를 왜 분리했나요?

Controller는 HTTP 요청과 응답, 입력 바인딩에 집중하고 Service는 업무 규칙과
여러 저장소·외부 시스템의 조합을 담당하게 했습니다. 이렇게 하면 HTTP와 무관한
업무 로직을 테스트하기 쉽고, 같은 Service를 다른 진입점에서도 재사용할 수 있습니다.

### Q9. `app.js`가 Service를 직접 호출하나요?

아닙니다. `app.js`는 HTTP URL을 호출하고 Controller가 Service를 호출합니다.

```text
app.js -> Controller -> Service -> Repository/Storage/외부 API
```

### Q10. 현재 구조의 가장 큰 장애 지점은 어디인가요?

FastAPI, MinIO, PostgreSQL, Ollama, Gemini, 네이버 API 등 네트워크 경계를
넘는 지점입니다. 그래서 호출 timeout, 예외 처리, fallback, 캐시, 데이터 없음
응답을 적용했습니다. 다만 운영 환경에서는 재시도 정책, circuit breaker,
분산 캐시와 모니터링을 추가할 수 있습니다.

## 2. Spring Boot와 API 설계

### Q11. `/api/internal/analysis/sources`는 무엇을 반환하나요?

Spring Boot가 DB의 주차장과 asset 메타데이터를 확인해 FastAPI가 분석할 수 있는
목록을 반환합니다. `partitionKey`, Spring Boot의 영상 URL, `slotLayoutJson`이
포함됩니다. 영상 바이너리 자체를 `/sources`에서 반환하지는 않습니다.

### Q12. FastAPI는 영상을 어떻게 받나요?

먼저 `/api/internal/analysis/sources`를 조회합니다. 이후 각 `videoUrl`을
호출하면 `AnalysisSourceController`가 DB에서 object key를 찾고 StorageService를
통해 MinIO의 영상을 스트리밍합니다. FastAPI는 이를 `/cache` volume에 임시
저장하고 OpenCV로 읽습니다.

### Q13. `/sources`에 모든 주차장이 나오지 않는 이유는 무엇인가요?

다음 조건을 모두 만족해야 합니다.

- `partitionKey`가 존재함
- `slotLayoutJson`이 비어 있지 않음
- VIDEO asset이 DB에 존재함
- 해당 object key가 MinIO에 실제로 존재함

영상만 업로드하고 슬롯 편집을 완료하지 않은 주차장은 분석 대상에서 제외됩니다.

### Q14. 주차 현황 조회에서 FastAPI를 매번 호출하지 않는 이유는 무엇인가요?

YOLO 추론은 일반 조회보다 무겁고, 여러 사용자가 동시에 조회하면 불필요한
호출이 반복됩니다. `ParkingStatusService`가 5초마다 FastAPI `/status`를
호출하고 메모리에 저장하므로 웹 요청은 빠르게 응답할 수 있습니다.

### Q15. FastAPI가 실패하면 캐시는 어떻게 됩니까?

상태 갱신 예외를 로그로 남기고 기존 캐시를 유지합니다. 최초 갱신 전에 실패해
캐시가 없으면 웹에는 상태 없음 또는 `NO_DATA`에 가까운 응답을 제공합니다.
따라서 일시적인 FastAPI 장애가 전체 웹 API 장애로 전파되지 않습니다.

### Q16. 현재 캐시 방식의 한계는 무엇인가요?

현재 캐시는 Spring Boot 인스턴스의 메모리에만 존재합니다. 서버를 재시작하면
사라지고, 여러 Spring Boot 인스턴스가 있으면 캐시가 서로 다를 수 있습니다.
운영 규모가 커지면 Redis 같은 공유 캐시와 상태 timestamp, stale 데이터 정책을
도입하는 것이 적절합니다.

### Q17. `GET /api/parking/status`와 지도 API의 차이는 무엇인가요?

`/api/parking/status`는 Spring Boot가 보관한 FastAPI 원시 상태 캐시에 가깝고,
`/api/campus/map`과 건물 상세 API는 DB의 캠퍼스·건물·주차장 구조와 상태를
결합해 화면에 맞는 응답으로 가공합니다.

### Q18. DTO를 사용하는 이유는 무엇인가요?

Entity를 그대로 외부에 노출하면 DB 구조가 API 계약으로 고정되고, 민감한 필드가
노출될 수 있습니다. DTO를 사용하면 화면에 필요한 필드만 제공하고 API 응답을
독립적으로 변경할 수 있습니다.

### Q19. 내부 분석 API를 인증 없이 허용한 이유는 무엇인가요?

FastAPI 컨테이너가 Spring Boot 내부 URL을 호출해야 하기 때문입니다. 현재는
Docker 내부 네트워크와 별도 내부 경로로 사용하고 있습니다. 운영 환경에서는
네트워크 격리만 믿지 않고 내부 서비스 토큰, mTLS 또는 gateway 정책을 추가하는
것이 더 안전합니다.

### Q20. API에서 데이터가 없을 때 어떻게 응답하나요?

상황에 따라 구분합니다. 상태 캐시가 없으면 `204 No Content`를 반환하고,
존재하지 않는 건물·주차장·파일은 `404`, 잘못된 입력은 `400`, 인증 실패는
`401` 또는 `403`으로 처리합니다. 화면에서는 데이터 없음 메시지를 표시합니다.

## 3. 영상 분석과 FastAPI

### Q21. YOLO 분석은 어떤 순서로 동작하나요?

FastAPI가 manifest를 읽고 영상을 다운로드한 뒤 OpenCV `VideoCapture`로 프레임을
읽습니다. 프레임을 설정된 크기로 줄여 YOLO에 전달하고, 차량 클래스의 bounding
box 중심점이 슬롯 사각형 안에 있는지 비교해 `occupied`를 계산합니다.

### Q22. 슬롯 점유 여부를 어떻게 판정하나요?

각 차량 bounding box의 중심점 `(bcx, bcy)`를 계산합니다. 슬롯의 사각형 좌표
`(rx1, ry1, rx2, ry2)`에 중심점이 포함되면 해당 슬롯을 점유로 표시합니다.
현재는 중심점 기반 사각형 판정입니다.

### Q23. 중심점 방식의 장단점은 무엇인가요?

구현이 단순하고 빠르며, 차량이 어느 슬롯에 있는지 직관적으로 판단할 수
있습니다. 하지만 차량이 슬롯 경계에 걸치거나 중심점이 다른 슬롯에 들어가는
경우 오판할 수 있습니다. 개선하려면 bounding box와 슬롯의 IoU, 점유 면적 비율,
시간적 안정화 또는 추적 알고리즘을 사용할 수 있습니다.

### Q24. 왜 모든 프레임에서 추론하지 않나요?

모든 프레임에 YOLO를 적용하면 CPU 사용량이 커지고 같은 영상에서 거의 같은
결과를 반복 계산하게 됩니다. 현재는 `frame_interval`과
`analysis_interval`을 함께 사용해 분석 빈도를 제한하고 PyTorch 스레드 수도
제한했습니다.

### Q25. CPU 사용량을 낮추기 위해 어떤 조치를 했나요?

- 실제 YOLO 분석 최소 간격을 10초로 설정
- 루프 자체에 sleep을 적용
- `torch.set_num_threads(2)` 적용
- `OMP_NUM_THREADS`, `MKL_NUM_THREADS`를 2로 제한
- 추론 전 프레임 해상도를 `854x480`으로 축소

정확도와 최신성, CPU 사용량 사이의 trade-off가 있으므로 운영 환경에서는
GPU 사용 여부와 영상 수에 따라 값을 조정해야 합니다.

### Q26. FastAPI의 `/status`는 무엇을 캐시하나요?

각 `partitionKey`별로 total, available, disabled_available 요약과 슬롯별
`slot_id`, `type`, `status`, `center`를 메모리에 보관합니다. 이 값은 DB에
저장되는 영구 이력 데이터가 아니라 최신 분석 스냅샷입니다.

### Q27. 영상이 끝나면 어떻게 하나요?

`VideoCapture.read()`가 실패하면 프레임 위치를 0으로 되돌려 영상을 반복 재생합니다.
실시간 카메라로 변경할 경우에는 재연결, 지연 감지, 프레임 드롭 정책이 추가로
필요합니다.

### Q28. FastAPI와 Spring Boot 사이에서 영상 원본을 왜 직접 공유하지 않나요?

원본 저장은 MinIO로 통일하고 Spring Boot가 접근 정책과 메타데이터를 관리하기
위해서입니다. FastAPI는 manifest를 통해 승인된 분석 대상만 받아 사용하며,
로컬 `images/`, `map/`, `videos/` 폴더에 의존하지 않습니다.

### Q29. `parking_yolo`를 삭제하면 어떻게 되나요?

Spring Boot의 주차장 등록·슬롯 편집·상태 API 구조는 남지만 영상에서 차량을
탐지할 주체가 없어집니다. 따라서 `occupied`와 `available` 계산이 중단됩니다.
Spring Boot와 FastAPI는 중복이 아니라 관리/API 계층과 추론 계층으로 분리되어
있습니다.

### Q30. 현재 분석의 정확도를 개선한다면 무엇을 하겠습니까?

주차장별 영상에 맞는 학습 데이터와 검증셋을 만들고, 차량 클래스·conf threshold를
튜닝하겠습니다. 슬롯별 IoU 판정, 차량 추적, 여러 프레임의 majority vote,
카메라 캘리브레이션을 적용해 순간적인 오탐을 줄이겠습니다.

## 4. PostgreSQL, JPA, MinIO

### Q31. PostgreSQL을 선택한 이유는 무엇인가요?

사용자, 캠퍼스, 건물, 주차장, asset, 알림 사이의 관계가 명확하고 트랜잭션과
제약 조건이 필요합니다. PostgreSQL은 관계형 모델과 Docker 운영이 적합하며,
H2와 달리 개발·테스트·운영의 DB 종류를 통일할 수 있습니다.

### Q32. 핵심 엔티티 관계를 설명해 주세요.

`Campus 1:N Building 1:N ParkingLot` 구조이고, `ParkingLot 1:N
ParkingLotAsset`입니다. 사용자는 저장 주차 위치, 알림 규칙, 알림 레코드와
연결됩니다. 실시간 점유 상태는 PostgreSQL에 저장하지 않고 FastAPI와 Spring
Boot 메모리 캐시에서 관리합니다.

### Q33. 실시간 상태를 DB에 저장하지 않은 이유는 무엇인가요?

상태는 영상 분석 때마다 자주 바뀌는 휘발성 스냅샷이므로 매 프레임 또는 매 분석
결과를 DB에 저장하면 쓰기 부하와 이력 정리 문제가 생깁니다. 현재 요구사항은
최신 상태 조회이므로 메모리 캐시가 단순합니다. 이력·통계가 필요해지면 별도
시계열 테이블이나 이벤트 저장소를 설계할 수 있습니다.

### Q34. `slotLayoutJson`을 JSON 문자열로 저장한 이유는 무엇인가요?

슬롯 수와 위치가 주차장마다 다르고 웹 편집기에서 한 번에 저장되는 구조라
초기 구현에서는 주차장 단위 JSON 저장이 단순했습니다. 별도 슬롯 테이블보다
편집 저장이 간단하지만, 슬롯별 검색·통계·무결성 검사가 중요해지면
`parking_slots` 테이블로 정규화할 수 있습니다.

### Q35. MinIO와 PostgreSQL의 역할 차이는 무엇인가요?

MinIO는 영상·이미지 바이너리를 저장하고 PostgreSQL은 object key, 파일명,
MIME type, 크기, 연결 주차장 같은 메타데이터를 저장합니다. DB에 대용량 바이너리를
넣지 않아 DB 백업과 조회 부담을 줄였습니다.

### Q36. StorageService는 MinIO 메타데이터를 어떻게 가져오나요?

현재 업로드 시 `MultipartFile`에서 크기·MIME type·원본 파일명을 읽고,
`StorageService.put()`에 전달해 `ParkingLotAsset`에 저장합니다. 이후
`exists()`는 MinIO `statObject()`로 객체 존재 여부만 확인합니다. 실제 MinIO
stat 메타데이터를 일반 조회용으로 추출하는 기능은 현재 구현하지 않았습니다.

### Q37. 파일 업로드 흐름을 설명해 주세요.

`BuildingController`가 `MultipartFile`을 받고 `BuildingRegistrationService`가
주차장 저장과 파일 저장을 조정합니다. StorageService가 MinIO에 파일을 넣고,
반환된 `StoredObject`와 원본 파일 정보를 `ParkingLotAssetService`가 DB에
upsert합니다. 실패하면 이미 저장된 asset을 삭제하는 보상 정리를 수행합니다.

### Q38. DB 저장은 성공했는데 MinIO 저장이 실패하면 어떻게 하나요?

현재 서비스는 파일 저장 과정에서 실패하면 생성한 주차장과 asset 상태를 정리하는
트랜잭션·보상 로직을 사용합니다. DB 트랜잭션과 MinIO는 서로 다른 시스템이므로
하나의 ACID 트랜잭션으로 묶이지 않습니다. 운영에서는 orphan object 정리 작업,
업로드 상태 필드, outbox 또는 재처리 큐를 추가할 수 있습니다.

### Q39. MinIO object key를 DB에 저장하는 이유는 무엇인가요?

실제 파일 위치와 외부 API URL을 분리하기 위해서입니다. 파일이 MinIO에 있어도
클라이언트는 object key를 알 필요가 없고, Spring Boot가 권한·응답 헤더·스트리밍을
통제할 수 있습니다.

### Q40. JPA에서 주의할 점은 무엇인가요?

Entity를 API에 직접 반환하지 않고 DTO로 변환하며, 연관관계 조회 시 필요한
범위를 확인해야 합니다. `LAZY` 로딩과 트랜잭션 범위를 고려하고, 목록 조회에서
연관 엔티티를 반복 조회하는 N+1 문제가 생기면 fetch join, entity graph 또는
전용 쿼리로 개선합니다.

## 5. 인증·보안

### Q41. JWT 인증 흐름을 설명해 주세요.

로그인 성공 시 `JwtUtil`이 username, 발급 시각, 만료 시각을 포함한 JWT를
서명해 반환합니다. 클라이언트는 `Authorization: Bearer <token>`으로 보내고,
`JwtAuthenticationFilter`가 토큰을 검증해 `SecurityContext`에 인증 주체를
등록합니다. 이후 Service가 현재 사용자 기준으로 데이터를 조회합니다.

### Q42. JWT를 사용한 이유는 무엇인가요?

서버 세션 저장소 없이 요청 자체에 인증 정보를 담을 수 있어 Docker 환경에서
수평 확장하기 쉽고, REST API와 브라우저 클라이언트에 적합합니다. 반면 토큰
강제 폐기와 탈취 대응이 어렵기 때문에 짧은 만료 시간, HTTPS, refresh token,
토큰 저장 보안이 운영에서 필요합니다.

### Q43. 인증과 인가의 차이는 무엇인가요?

인증은 요청자가 누구인지 확인하는 과정이고, 인가는 그 사용자가 해당 자원에
접근할 권한이 있는지 확인하는 과정입니다. JWT 필터는 주로 인증을 만들고,
사용자별 주차 위치·알림·등록 데이터의 소유자 확인은 Service에서 인가로 처리합니다.

### Q44. 공개 API와 보호 API를 어떻게 나눴나요?

지도·주차 현황·장소 검색·음성 질의처럼 조회 중심 기능은 공개하고, 사용자 정보,
내 주차 위치, 알림, 건물·주차장 등록과 삭제는 인증을 요구합니다. `SecurityConfig`
의 URL matcher와 Service의 현재 사용자 검사를 함께 사용합니다.

### Q45. 잘못된 JWT는 어떻게 처리하나요?

서명 검증이나 만료 검증에 실패하면 `SecurityContext`에 인증을 등록하지 않습니다.
보호된 API는 Spring Security가 인증 오류로 처리하고, 공개 API는 인증 없이
계속 접근할 수 있습니다. 토큰 오류 내용을 클라이언트에 자세히 노출하지 않는
것이 안전합니다.

### Q46. JWT secret을 코드에 둬도 되나요?

운영에서는 안 됩니다. 현재 기본값은 Docker 실행 편의를 위한 fallback일 뿐이고,
실제 배포에서는 `SMARTPARKING_JWT_SECRET` 환경변수나 secret manager를 사용해야
합니다. 키가 바뀌면 기존 토큰을 무효화할 수 있다는 운영 정책도 필요합니다.

## 6. 음성·LLM·외부 API

### Q47. 음성인식은 백엔드가 처리하나요?

음성 자체의 브라우저 인식은 `app.js`의 Web Speech API가 처리합니다. 인식된
텍스트 또는 사용자가 입력한 텍스트가 `POST /api/voice/ask`로 전달되고,
Spring Boot가 주차 현황 기반 답변을 만든 뒤 LLM을 호출합니다. 즉 브라우저
음성인식과 서버의 답변 생성은 분리되어 있습니다.

### Q48. LLM에게 질문만 보내지 않고 사실 답변을 먼저 만드는 이유는 무엇인가요?

LLM이 존재하지 않는 주차장이나 숫자를 만들어내는 환각을 줄이기 위해서입니다.
`VoiceAnswerService`가 현재 캐시된 상태에서 대상 주차장을 찾고 빈자리 수를
규칙 기반으로 계산한 뒤, Gemini/Ollama에는 그 정확한 답변을 자연스럽게 다듬도록
요청합니다.

### Q49. Gemini와 Ollama의 fallback 순서는 어떻게 됩니까?

질문이 비어 있으면 안내 문장을 즉시 반환합니다. 정상 질문이면 규칙 기반 사실
답변을 만든 뒤 Gemini를 먼저 호출하고, 키가 없거나 빈 응답·호출 실패이면
Ollama로 fallback합니다. 둘 다 실패하거나 빈 응답이면 규칙 기반 사실 답변을
반환하고, 전체 예외 상황에서는 사용자 안내 문장을 반환합니다.

### Q50. Gemini와 Ollama를 함께 사용한 이유는 무엇인가요?

Gemini는 자연어 품질이 좋고, Ollama는 외부 API 키나 네트워크에 덜 의존하는
fallback이 될 수 있습니다. 다만 Ollama도 모델 로딩과 CPU·메모리 비용이 있으므로
실제 운영에서는 비용, 응답 시간, 개인정보 정책을 기준으로 선택해야 합니다.

### Q51. LLM 호출 timeout은 얼마이며 왜 필요한가요?

Gemini는 8초, Ollama는 12초 timeout을 적용했습니다. 외부 모델이 응답하지 않을
때 HTTP 요청이 무한히 대기하면 서버 스레드와 사용자 요청이 쌓이므로 제한 시간이
필요합니다. timeout 뒤에는 다음 fallback 또는 정확한 기본 답변으로 넘어갑니다.

### Q52. 장소 검색 API를 백엔드에서 호출하는 이유는 무엇인가요?

네이버 API 키와 secret을 브라우저에 노출하지 않고, 외부 응답 형식을 프로젝트
DTO로 변환하기 위해서입니다. `NaverSearchService`가 Spring Boot 내부에서
WebClient로 호출하고 `GeoSearchController`가 웹에 필요한 결과만 제공합니다.

### Q53. LLM이 잘못된 답변을 하면 어떻게 방어할 수 있나요?

현재는 규칙 기반 사실 답변과 프롬프트 제약으로 숫자·장소 변경을 막고 있습니다.
더 강하게 보장하려면 LLM 응답을 구조화된 JSON으로 받고 숫자와 장소를 원본 사실과
비교하거나, 숫자가 포함된 답변을 검증해 불일치 시 사실 답변으로 대체해야 합니다.

## 7. 예외 처리·장애 대응

### Q54. 예외를 어디에서 처리해야 하나요?

입력 형식과 HTTP 상태 변환은 Controller 또는 전역 예외 처리 계층에서,
외부 시스템의 timeout·응답 누락은 해당 Client Service에서, 업무 규칙은
도메인 Service에서 처리하는 것이 좋습니다. 현재 프로젝트는 각 Client와
Service에서 기능별 fallback을 적용하고 있습니다.

### Q55. 모든 예외를 `catch (Exception)`으로 잡는 것은 좋은가요?

일반적으로는 구체적인 예외를 잡는 편이 좋습니다. 현재 외부 API 호출은 외부
라이브러리와 네트워크 예외를 사용자 응답으로 변환해야 하므로 넓게 잡고 로그와
fallback을 적용한 부분이 있습니다. 운영 코드에서는 예외 종류별 분류, 원인 로그,
공통 오류 응답을 더 세밀하게 구성하는 것이 개선점입니다.

### Q56. 외부 API 재시도를 무조건 적용하면 안 되는 이유는 무엇인가요?

재시도는 일시적인 네트워크 오류에는 유용하지만, timeout이 긴 요청을 반복하면
부하와 지연이 커지고 POST 요청은 중복 작업을 만들 수 있습니다. idempotency,
최대 재시도 횟수, exponential backoff, circuit breaker를 함께 설계해야 합니다.

### Q57. 기존 캐시를 유지하는 것과 오래된 데이터를 보여주는 문제는 어떻게 균형을 잡나요?

현재는 일시 장애 중에도 사용자에게 마지막으로 확인된 상태를 제공하는 쪽을
선택했습니다. 대신 `last_update` timestamp를 함께 내려 데이터가 오래됐음을
표시해야 합니다. 일정 시간 이상 stale이면 `NO_DATA`나 장애 상태로 전환하는
정책을 추가하는 것이 좋습니다.

### Q58. 파일 업로드 실패 시 어떤 정리가 필요합니까?

DB에 주차장만 남거나 MinIO에 orphan object가 남지 않도록 순서를 설계해야 합니다.
현재 업로드 중 예외가 나면 저장된 asset을 찾아 MinIO object를 삭제하는 보상
정리를 수행합니다. 완전한 분산 트랜잭션은 아니므로 주기적인 orphan 정리도
운영에서 고려해야 합니다.

## 8. 테스트·운영·개선

### Q59. 테스트 DB로 H2를 사용하지 않은 이유는 무엇인가요?

운영 DB가 PostgreSQL이므로 테스트도 PostgreSQL을 사용해 SQL 방언과 타입,
제약 조건 차이로 인한 오작동을 줄였습니다. Testcontainers가 PostgreSQL
컨테이너를 테스트 수명 동안 실행하므로 개발·운영과 가까운 통합 테스트가 됩니다.

### Q60. Testcontainers의 단점은 무엇인가요?

Docker 실행이 필요하고 초기 컨테이너 시작 시간이 추가됩니다. 이미지 다운로드와
환경 의존성도 있습니다. 반면 H2 대체로 인한 호환성 문제를 줄이고 실제 DB와
가까운 검증을 할 수 있다는 장점이 있습니다.

### Q61. 어떤 실패 시나리오를 테스트했나요?

FastAPI 미실행, 외부 응답 timeout, Gemini 키 누락, Gemini와 Ollama 동시 실패,
잘못된 JWT, 인증 없이 보호 API 호출, 존재하지 않는 ID, 빈 주차장 데이터,
파일 저장 실패와 같은 상황을 우선 테스트합니다. 핵심은 예외가 발생하는지만
보는 것이 아니라 HTTP 상태와 fallback 응답이 의도한 계약인지 확인하는 것입니다.

### Q62. 현재 테스트에서 더 보강할 부분은 무엇인가요?

Controller의 HTTP 상태 계약, MinIO 통합 테스트, FastAPI manifest와 영상 스트림
계약 테스트, 캐시 stale 정책, 동시 업로드, 파일 크기 제한, LLM 응답 검증을
추가할 수 있습니다. 특히 외부 API는 실제 호출 대신 WireMock 같은 stub으로
timeout·잘못된 JSON·빈 응답을 재현하면 안정적입니다.

### Q63. 서비스가 느려지면 무엇부터 측정하겠습니까?

요청별 latency와 상태 코드를 기록하고, DB 쿼리 시간, FastAPI `/status` 호출 시간,
MinIO 스트리밍 시작 시간, Gemini/Ollama 응답 시간을 분리해 측정합니다. 로그만
보지 않고 correlation ID, metrics, tracing을 추가해 병목을 구간별로 확인하겠습니다.

### Q64. 현재 Docker 구조에서 서비스 시작 순서의 문제는 무엇인가요?

`depends_on`은 컨테이너 시작 또는 health 상태를 제어하지만 애플리케이션이
실제로 요청을 받을 준비가 되었는지까지 항상 보장하지는 않습니다. 현재 PostgreSQL은
healthcheck를 사용하고, 외부 서비스 호출은 timeout과 재시도를 고려해야 합니다.
운영에서는 각 서비스 health endpoint와 readiness를 분리하는 것이 좋습니다.

### Q65. FastAPI CPU 사용량이 높으면 어떤 선택지가 있나요?

분석 주기와 입력 해상도를 낮추고, Torch/OpenMP 스레드를 제한하며, 영상 수를
분산하거나 GPU 추론으로 이동할 수 있습니다. 다만 분석 주기를 낮추면 최신성이
떨어지고 해상도를 낮추면 작은 차량 탐지 정확도가 떨어지므로 측정 기반으로
조정해야 합니다.

### Q66. 현재 구조를 운영 수준으로 개선한다면 우선순위는 무엇인가요?

첫째, JWT secret·MinIO 자격 증명·API 키를 secret manager로 이동합니다.
둘째, 내부 분석 API 인증과 HTTPS를 적용합니다. 셋째, Redis 캐시와 상태 stale
정책을 도입합니다. 넷째, metrics, tracing, alerting을 추가합니다. 다섯째,
FastAPI 분석 worker를 영상 수와 GPU 자원에 맞게 분리·확장합니다.

## 9. 꼬리 질문용 짧은 답변

### Q67. 왜 WebClient를 사용했나요?

Spring 생태계에서 HTTP 외부 연동을 구성하기 쉽고 timeout과 응답 변환을 체계적으로
적용할 수 있기 때문입니다. 현재 호출부에서 `.block()`을 사용해 동기적인 Service
흐름으로 결과를 사용하지만, 높은 동시성이 필요하면 reactive end-to-end 또는
비동기 작업 큐로 확장할 수 있습니다.

### Q68. `@Scheduled` 사용 시 주의점은 무엇인가요?

주기 작업이 중복 실행되지 않는지, 외부 호출이 작업 주기보다 오래 걸리지 않는지,
예외가 다음 실행을 막지 않는지 확인해야 합니다. 다중 인스턴스 환경에서는 모든
인스턴스가 같은 작업을 실행할 수 있으므로 분산 lock이나 전용 worker가 필요합니다.

### Q69. 음성인식이 잘 안 되면 백엔드 문제인가요?

반드시 그렇지는 않습니다. 브라우저 Web Speech API의 권한, 브라우저 지원,
마이크 입력, HTTPS 또는 localhost 여부가 먼저 영향을 줍니다. 인식된 텍스트가
서버에 도착한 뒤의 답변 생성은 Spring Boot 영역이며, `app.js`는 중간 결과와
권한·무음·네트워크 오류를 사용자에게 표시합니다.

### Q70. 즐겨찾기는 DB에 저장되나요?

현재는 서버 DB가 아니라 `app.js`의 사용자명별 `localStorage`에 저장됩니다.
로그인 사용자별 키를 사용해 같은 브라우저에서 계정 간 목록이 섞이지 않도록
했지만, 다른 브라우저와 동기화되지는 않습니다. 서버 동기화가 필요하면
`User-FavoriteParkingLot` 관계와 CRUD API를 추가해야 합니다.

### Q71. 왜 실시간이라는 표현에 주의해야 하나요?

현재 구조는 카메라 프레임마다 즉시 반영하는 방식이 아니라 FastAPI 분석 주기와
Spring Boot 폴링 주기가 있습니다. 따라서 정확히는 주기적으로 갱신되는 near-real-time
상태입니다. 면접에서는 분석 주기, 캐시 timestamp, 화면 갱신 주기를 함께 설명해야
합니다.

### Q72. 본인이 구현한 부분과 외부 라이브러리의 역할을 구분해 주세요.

YOLO 추론 엔진 자체는 Ultralytics를 사용했고, 제가 구현한 부분은 영상 소스
manifest 연동, 슬롯 좌표 파싱, 차량 중심점과 슬롯 비교, 상태 변환, Spring Boot
캐시·API 연동입니다. 인증은 Spring Security와 JJWT를 사용하고, 그 위에 프로젝트의
사용자 흐름과 권한 정책을 구성했습니다.

### Q73. 가장 어려웠던 기술적 문제와 해결 방법은 무엇인가요?

분석 서버와 웹 API의 실행 주기와 장애 경계를 맞추는 일이 어려웠습니다. FastAPI를
조회 요청마다 직접 호출하지 않고 주기 캐시로 분리하고, timeout과 기존 캐시 유지,
LLM fallback을 적용해 외부 서버 상태가 사용자 요청에 직접 전파되지 않도록 했습니다.

### Q74. 이 프로젝트에서 가장 중요한 설계 판단은 무엇인가요?

정확한 주차 수치는 규칙 기반으로 계산하고 LLM은 문장 표현만 담당하게 한 것입니다.
주차 현황처럼 숫자 정확성이 중요한 도메인에서 생성 모델을 사실 계산의 주체로
두지 않아 환각 위험을 줄였습니다.

### Q75. 면접 마지막에 프로젝트의 한계를 어떻게 말하겠습니까?

현재 상태 캐시는 단일 인스턴스 메모리 기반이고, 내부 분석 API 인증과 고급
관측성이 부족합니다. 영상 분석은 중심점·사각형 판정이라 경계 상황의 오탐 가능성이
있고, 즐겨찾기도 브라우저 localStorage 기반입니다. 다음 단계로 Redis, 내부 인증,
분산 worker, 추론 정확도 개선, 구조화된 LLM 응답 검증을 적용하겠습니다.

## 면접 답변 시 주의할 표현

- `app.js`가 Service를 직접 호출한다고 말하지 말고, Controller API를 호출한다고 말한다.
- MinIO에서 메타데이터를 자동 추출한다고 말하지 말고, 업로드 시 `MultipartFile`에서
  얻은 메타데이터를 DB에 저장하고 현재는 MinIO 존재 여부를 `statObject()`로 확인한다고 말한다.
- 완전한 실시간이라고 단정하지 말고, FastAPI 분석 주기와 Spring Boot 캐시 폴링 기반의
  near-real-time이라고 설명한다.
- 즐겨찾기가 사용자 계정 DB에 동기화된다고 말하지 말고, 현재는 사용자명별 브라우저
  `localStorage`라고 설명한다.
- LLM이 주차 수치를 계산한다고 말하지 말고, Spring Boot가 계산한 사실 답변을
  Gemini/Ollama가 자연어로 다듬는 구조라고 설명한다.

---

# 백엔드·클라우드 엔지니어 관점 추가 질문

## 10. 서버 운영과 네트워크

### Q76. 컨테이너에서 `localhost`를 사용하면 왜 문제가 생기나요?

컨테이너 안의 `localhost`는 호스트나 다른 컨테이너가 아니라 자기 자신을
가리킵니다. 따라서 Spring Boot에서 PostgreSQL을 호출할 때는 `localhost`가
아니라 Compose 서비스명인 `postgres`를 사용해야 합니다. FastAPI와 MinIO도
각각 `fastapi`, `minio`로 연결합니다.

### Q77. Docker Compose의 서비스명 DNS는 어떻게 동작하나요?

Compose가 공통 네트워크를 만들고 서비스명을 내부 DNS 이름으로 등록합니다.
컨테이너는 `postgres:5432`, `fastapi:8000`처럼 서비스명으로 접근할 수 있습니다.
이름 기반 연결은 컨테이너 IP가 재생성되어도 동작하므로 고정 IP보다 운영에
적합합니다.

### Q78. 포트 매핑과 컨테이너 간 통신의 차이는 무엇인가요?

`8080:8080` 같은 ports 설정은 호스트에서 컨테이너로 접근할 때 필요합니다.
반면 같은 Compose 네트워크의 컨테이너끼리는 호스트 포트가 아니라 컨테이너
포트와 서비스명으로 통신합니다. Spring Boot가 FastAPI를 호출할 때
`http://fastapi:8000`을 사용하는 이유입니다.

### Q79. Dockerfile을 어떻게 최적화할 수 있나요?

작은 base image를 사용하고, 의존성 파일을 먼저 복사해 Docker layer cache를
활용하며, 불필요한 빌드 도구와 캐시를 최종 이미지에 넣지 않습니다. 현재
FastAPI와 Spring Boot를 각각 이미지로 빌드하고, 모델 weight·MinIO·PostgreSQL
데이터는 volume으로 이미지와 분리했습니다.

### Q80. 컨테이너에 데이터를 저장하면 안 되는 이유는 무엇인가요?

컨테이너는 재생성될 수 있고 writable layer는 영속 저장소로 적합하지 않습니다.
현재 PostgreSQL·MinIO·Ollama 모델·FastAPI 임시 cache를 Docker volume에 연결해
컨테이너 생명주기와 데이터를 분리했습니다.

### Q81. Compose의 `depends_on`만으로 서비스 준비를 보장할 수 있나요?

아닙니다. `depends_on`은 시작 순서나 지정된 health 상태를 제어할 뿐, 애플리케이션이
모든 요청을 받을 준비가 됐다는 보장은 제한적입니다. healthcheck, 애플리케이션
내부 재시도, readiness endpoint를 함께 사용해야 합니다.

### Q82. 헬스체크와 readiness check의 차이는 무엇인가요?

헬스체크는 프로세스가 살아 있는지 확인하고, readiness는 트래픽을 받아도 되는지
확인합니다. FastAPI `/health`는 기본 생존 확인에 가깝고, 운영 환경에서는 YOLO 모델
로드 여부와 Spring Boot의 DB·MinIO 연결 준비 여부를 분리해 제공하는 것이 좋습니다.

### Q83. Reverse Proxy를 도입한다면 어떤 역할을 맡기겠습니까?

Nginx나 cloud load balancer를 앞에 두고 TLS 종료, 도메인 라우팅, 압축, access log,
rate limiting을 맡길 수 있습니다. 외부에는 Spring Boot만 노출하고 FastAPI·PostgreSQL·
MinIO 관리 포트는 내부 네트워크에 두는 것이 바람직합니다.

### Q84. 업로드 영상이 큰데 API 서버에서 처리할 때의 문제는 무엇인가요?

2GB까지 허용하는 현재 방식은 Spring Boot의 네트워크와 메모리·디스크 처리 부담이
커질 수 있습니다. 운영에서는 presigned URL로 클라이언트가 object storage에
직접 업로드하고, 완료 이벤트만 Spring Boot가 받아 DB 메타데이터를 기록하는
방식으로 개선할 수 있습니다.

### Q85. HTTP timeout을 어느 계층에 둬야 하나요?

클라이언트, reverse proxy, Spring Boot WebClient, FastAPI, DB 등 각 네트워크
경계에 목적에 맞는 timeout이 필요합니다. 너무 길면 장애 요청이 누적되고 너무
짧으면 정상적인 모델 응답도 실패하므로 p95/p99 latency와 fallback 시간을
기준으로 정해야 합니다.

## 11. 클라우드 전환 설계

### Q86. 이 프로젝트를 AWS에 배포한다면 서비스를 어떻게 매핑하겠습니까?

Spring Boot와 FastAPI는 ECS/Fargate 또는 EKS의 컨테이너로 배포할 수 있습니다.
PostgreSQL은 RDS, MinIO는 S3, Ollama는 GPU가 필요한 경우 EC2 또는 별도 추론
서비스, 외부 진입점은 ALB로 구성할 수 있습니다. 단순한 초기 배포라면 ECS가
Kubernetes보다 운영 부담이 작습니다.

### Q87. MinIO를 S3로 바꿀 때 애플리케이션 변경을 줄이는 방법은 무엇인가요?

현재처럼 `StorageService` 인터페이스 뒤에 저장소 구현을 숨기면 됩니다. S3 SDK를
사용하는 구현체를 추가하고 설정으로 MinIO 구현체와 선택하게 하면 Controller와
도메인 Service는 변경하지 않을 수 있습니다. object key 규칙과 content type
정책은 공통으로 유지해야 합니다.

### Q88. PostgreSQL을 RDS로 옮길 때 확인할 것은 무엇인가요?

연결 주소·TLS·보안 그룹·계정 권한·백업 정책·DB timezone·connection pool을
확인해야 합니다. `ddl-auto=update`를 운영 마이그레이션 전략으로 계속 사용하기보다
Flyway 또는 Liquibase로 스키마 변경 이력을 관리하는 것이 안전합니다.

### Q89. 클라우드 보안 그룹은 어떻게 구성하겠습니까?

인터넷에는 ALB의 443만 열고, Spring Boot는 ALB에서 오는 트래픽만 허용합니다.
FastAPI는 Spring Boot 보안 그룹에서만 접근하도록 하고, RDS는 애플리케이션
보안 그룹에서만 5432를 허용합니다. PostgreSQL·MinIO console·Ollama 관리 포트는
공개하지 않습니다.

### Q90. 비밀값은 어떻게 관리해야 하나요?

JWT secret, DB password, MinIO key, Gemini·네이버 API key를 이미지나 Git에 넣지
않고 AWS Secrets Manager, Parameter Store, Kubernetes Secret 같은 별도 저장소에서
주입해야 합니다. 로그에 Authorization 헤더나 secret이 출력되지 않는지도 확인해야
합니다.

### Q91. object storage 파일을 공개 URL로 제공하지 않는 이유는 무엇인가요?

파일 접근을 애플리케이션에서 통제하고 사용자의 권한·파일 상태를 확인하기
위해서입니다. 규모가 커지면 권한을 검증한 뒤 짧은 만료 시간의 presigned URL을
발급하는 방식으로 Spring Boot의 영상 스트리밍 부담을 낮출 수 있습니다.

### Q92. 다중 인스턴스로 확장할 때 현재 구조의 문제는 무엇인가요?

Spring Boot의 주차 상태 캐시가 각 인스턴스 메모리에 있고 `@Scheduled` 작업도
인스턴스마다 실행될 수 있습니다. Redis 같은 공유 캐시, 분산 lock, 전용 polling
worker를 도입해야 상태 일관성과 중복 호출을 제어할 수 있습니다.

### Q93. FastAPI를 여러 대로 확장할 때 무엇을 나누겠습니까?

영상 소스별 또는 주차장별 분석 작업을 큐에 넣고 worker가 분배받도록 할 수
있습니다. 모델을 worker마다 로드하면 메모리 비용이 커지므로 GPU 수, batch,
작업 분배 방식, 결과 저장소를 함께 고려해야 합니다. 단순히 컨테이너 수만
늘리면 같은 영상을 중복 분석할 수 있습니다.

### Q94. 영상 분석을 비동기 작업으로 바꾼다면 어떤 구조가 되나요?

등록 API는 영상과 분석 요청을 저장하고 즉시 job ID를 반환합니다. queue가 작업을
전달하면 분석 worker가 처리하고, 결과 저장소에 상태를 기록합니다. 웹은 polling,
SSE 또는 WebSocket으로 상태를 받습니다. 현재의 단순 주기 worker보다 확장성은
좋지만 queue·재처리·중복 방지 설계가 추가됩니다.

## 12. 데이터베이스 운영과 성능

### Q95. DB connection pool을 왜 관리해야 하나요?

DB 연결은 비용이 크고 PostgreSQL의 동시 연결 수도 제한됩니다. pool을 너무 크게
잡으면 DB가 과부하되고, 너무 작게 잡으면 애플리케이션 대기가 길어집니다. 인스턴스
수와 요청량, DB max connection을 기준으로 pool 크기를 산정해야 합니다.

### Q96. 인덱스는 어디에 필요할까요?

조회 조건과 정렬에 반복적으로 사용되는 `partitionKey`, 건물 ID와 정렬 순서,
주차장 asset의 `(parking_lot_id, asset_type)`, 사용자 기준 알림·주차 위치 컬럼을
검토할 수 있습니다. 인덱스는 조회를 빠르게 하지만 쓰기와 저장 공간 비용이 있으므로
실제 실행 계획으로 검증해야 합니다.

### Q97. N+1 문제가 발생하면 어떻게 찾고 해결하나요?

SQL 로그와 APM으로 동일한 연관 조회가 반복되는지 확인합니다. fetch join,
`@EntityGraph`, projection DTO, batch size 등을 상황에 맞게 적용합니다. 단,
fetch join으로 여러 collection을 한 번에 가져오면 중복 row와 메모리 증가가
생길 수 있으므로 쿼리별 결과 크기를 확인해야 합니다.

### Q98. DB 백업과 복구 전략은 어떻게 세우겠습니까?

RDS라면 자동 백업과 point-in-time recovery를 활성화하고, 별도 리전에 백업을
복제할 수 있습니다. MinIO/S3 영상도 versioning과 lifecycle, cross-region
replication을 검토해야 합니다. 백업은 존재 여부보다 실제 복구 테스트와 RPO·RTO가
중요합니다.

### Q99. RPO와 RTO를 설명해 주세요.

RPO는 장애 시 허용할 수 있는 데이터 손실 시점이고, RTO는 서비스 복구까지 허용하는
시간입니다. 주차 상태 캐시는 재계산 가능해 낮은 영속성 요구를 둘 수 있지만,
사용자·asset 메타데이터와 업로드 영상은 복구 정책을 별도로 가져야 합니다.

### Q100. 트랜잭션이 MinIO까지 보장하나요?

아닙니다. Spring 트랜잭션은 PostgreSQL에 적용되고 MinIO 작업은 별도 시스템입니다.
그래서 DB와 object storage 사이 불일치를 고려해 보상 삭제, 업로드 상태, 재처리
작업, orphan object 정리 같은 패턴이 필요합니다.

## 13. 관측성·배포·보안 운영

### Q101. 운영 로그에는 무엇을 남겨야 하나요?

요청 ID, endpoint, 상태 코드, latency, 외부 서비스명, timeout 여부, fallback 여부,
partitionKey 같은 추적 가능한 업무 식별자를 남길 수 있습니다. 비밀번호, JWT,
API key, 영상 내용은 기록하지 않아야 하며 구조화된 JSON 로그가 검색과 집계에
유리합니다.

### Q102. 어떤 metrics를 수집하겠습니까?

HTTP 요청 수·에러율·p95 latency, DB pool 사용률, FastAPI 분석 성공·실패 수,
마지막 분석 시각, 분석 queue depth, MinIO 업로드 실패 수, Gemini/Ollama fallback
비율, CPU·메모리·디스크 사용량을 수집하겠습니다.

### Q103. tracing이 필요한 이유는 무엇인가요?

한 번의 사용자 요청이 Spring Boot, FastAPI, DB, MinIO, LLM을 거치므로 로그만으로는
전체 지연 원인을 찾기 어렵습니다. trace ID를 전달하면 요청별 각 구간의 latency와
실패 위치를 확인할 수 있습니다. OpenTelemetry와 Jaeger 또는 클라우드 tracing
서비스를 사용할 수 있습니다.

### Q104. 배포 중 무중단 서비스를 어떻게 구현하겠습니까?

새 버전을 별도 task로 먼저 기동하고 health/readiness를 통과한 뒤 load balancer가
트래픽을 보내도록 rolling 또는 blue-green 배포를 사용합니다. DB 스키마는 구버전과
신버전이 잠시 함께 동작할 수 있는 backward-compatible 순서로 변경해야 합니다.

### Q105. CI/CD 파이프라인에는 무엇을 넣겠습니까?

코드 검사, Java 테스트, Testcontainers 통합 테스트, JavaScript·Python 문법 검사,
Docker image build, 취약점 검사, image registry push, staging 배포와 healthcheck를
순서대로 실행합니다. 운영 배포는 승인 단계와 rollback 가능한 이전 image tag를
남겨야 합니다.

### Q106. 컨테이너 이미지 취약점은 어떻게 관리하나요?

base image와 라이브러리를 정기적으로 업데이트하고 Trivy 같은 scanner로 CVE를
검사합니다. root가 아닌 사용자로 실행하고, 불필요한 패키지·shell·권한을 줄이며,
weight와 설정 secret을 이미지에 포함하지 않습니다.

### Q107. DDoS나 비정상적인 API 요청을 방어하려면 무엇이 필요합니까?

ALB/WAF의 rate limit과 IP 정책, 인증 API의 brute-force 방어, 업로드 크기·파일
형식 제한, request timeout, connection limit이 필요합니다. 음성 질의와 LLM 호출은
비용이 발생할 수 있으므로 사용자별 quota와 동시 요청 제한도 고려해야 합니다.

### Q108. 로그에 stack trace를 전부 남겨도 되나요?

개발에서는 원인 파악에 도움이 되지만 운영에서는 민감한 경로·키·요청 데이터가
노출될 수 있습니다. 내부 로그에는 exception type과 trace를 접근 통제 하에 남기고,
클라이언트에는 일반화된 오류 메시지와 request ID만 반환하는 것이 좋습니다.

### Q109. 클라우드 비용을 줄이려면 어떻게 하겠습니까?

YOLO 분석 주기와 해상도를 요구사항에 맞게 조정하고, 사용하지 않는 Ollama 모델과
volume을 정리하며, object storage lifecycle로 오래된 임시 파일을 삭제합니다.
항상 켜둘 필요가 없는 worker는 autoscaling 또는 schedule을 적용하고, 로그·백업의
보존 기간도 비용과 복구 요구를 함께 보고 결정합니다.

### Q110. 장애가 발생했을 때 조사 순서를 설명해 주세요.

먼저 사용자 영향과 발생 시각을 확인하고, load balancer·Spring Boot·외부 서비스의
health와 error rate를 확인합니다. trace ID나 요청 로그로 실패 구간을 좁힌 뒤 DB
connection, MinIO object, FastAPI 분석 상태, 최근 배포를 확인합니다. 완화 조치를
먼저 적용한 뒤 원인 분석과 재발 방지 작업을 별도로 정리합니다.

### Q111. 클라우드 엔지니어와 백엔드 엔지니어의 협업 경계는 어떻게 나누나요?

백엔드는 API 계약, 도메인 로직, DB 접근, 외부 연동과 애플리케이션 timeout을
정의합니다. 클라우드 엔지니어는 네트워크, IAM, secret, container runtime,
autoscaling, monitoring, backup을 설계합니다. 다만 healthcheck, resource limit,
배포 방식처럼 양쪽에 걸친 영역은 함께 계약을 정해야 합니다.

### Q112. 이 프로젝트를 실제 클라우드에 올리기 전에 가장 먼저 바꿀 것은 무엇인가요?

첫째, 기본 secret과 공개 관리 포트를 제거하고 IAM·secret manager를 적용합니다.
둘째, PostgreSQL과 object storage를 관리형 서비스로 분리하고 백업·복구를 검증합니다.
셋째, 메모리 캐시와 scheduled polling의 다중 인스턴스 문제를 해결합니다. 그 다음
관측성과 CI/CD를 추가해야 운영 중 원인을 추적할 수 있습니다.
