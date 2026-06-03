# AGENTS.md

## Project Purpose

YOLO 기반 영상 분석 기술을 활용한 실시간 주차 점유 감지 시스템

## Project Components

- FastAPI: YOLO 추론 서버 / 영상 분석 API
- Spring Boot: 사용자 API / 주차장 데이터 관리 / 인증 / 결과 조회
- Frontend or Client: 실시간 점유 현황 표시

Approval Requirements

NEVER modify source code automatically.

The following actions require explicit approval:

Creating files
Editing files
Deleting files
Refactoring code
Modifying configuration
Running migrations
Changing Docker configuration
Changing CI/CD configuration

Always ask:

"Do you approve this plan?"

Wait for a clear approval before proceeding.

## Core Rules

- 변경 전 반드시 Plan 제시
- Diff 확인 후 수정
- 보안 수정 시 원인 설명
- ARCHITECTURE.md 최신 상태 유지
- CHANGELOG.md 갱신
- 불필요한 대규모 리팩토링 금지
- 기존 동작을 깨지 않는 방향 우선
- 수정 후 실행/테스트 방법 제시
- 민감정보 하드코딩 금지

## Documentation Rules

- 구조 변경 시 ARCHITECTURE.md 갱신
- 기능 추가/수정 시 CHANGELOG.md 갱신
- 실행 방법이 바뀌면 README.md 갱신
- 새 환경변수가 필요하면 .env.example 갱신

## Git Rules

- 변경 단위는 작게 유지
- 커밋 메시지는 작업 내용을 명확히 작성
- 생성 파일과 수정 파일을 구분해서 설명

## Security Rules

- Never commit .env files.
- Keep only .env.example in the repository.
- Never hardcode secrets in source code.
- Prefer GitHub Secrets, Vault, or Secret Manager.
- Scan for secrets before commits.
- Report security implications of every change.

## Never read or expose:
- .ssh/*
- private keys
- credentials
- auth tokens
- secret files
unless explicitly requested.