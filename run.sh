#!/usr/bin/env bash
#
# P-Project 통합 실행 스크립트
#   - Spring Boot (웹 + API, 8080) : 백그라운드 실행, 로그는 파일로
#   - FastAPI (YOLO 추론 + 영상 창, 8000) : 포그라운드 실행 (영상 창 표시)
#
# 사용법:  ./run.sh
# 종료:    터미널에서 Ctrl+C  (두 서버 모두 함께 종료됨)
#
set -euo pipefail

# 스크립트 위치를 프로젝트 루트로 사용
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SB_DIR="$ROOT_DIR/springboot"
FA_DIR="$ROOT_DIR/fastapi/video_test"
SB_LOG="/tmp/p-project-springboot.log"

# Java 17
# - 기존 JAVA_HOME이 유효하면 그대로 사용
# - 아니면 현재 시스템의 java 위치에서 자동 탐지
detect_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    /usr/libexec/java_home -v 17 2>/dev/null && return 0
  fi

  if command -v java >/dev/null 2>&1; then
    local java_bin
    java_bin="$(command -v java)"
    java_bin="$(readlink -f "$java_bin" 2>/dev/null || realpath "$java_bin" 2>/dev/null || printf '%s\n' "$java_bin")"
    dirname "$(dirname "$java_bin")"
    return 0
  fi

  return 1
}

JAVA_HOME_DETECTED="$(detect_java_home || true)"
if [ -z "$JAVA_HOME_DETECTED" ]; then
  echo "[run.sh] ERROR: Java 17을 찾지 못했습니다. JDK 17을 설치한 뒤 JAVA_HOME을 설정하세요."
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_DETECTED"
export PATH="$JAVA_HOME/bin:$PATH"

# Gradle 캐시 경로:
# - 기본 ~/.gradle 이 쓰기 불가한 환경에서도 동작하도록
# - 프로젝트 내부 .gradle 을 기본값으로 사용
if [ -z "${GRADLE_USER_HOME:-}" ] || [ ! -w "${GRADLE_USER_HOME:-}" ]; then
  GRADLE_USER_HOME="$ROOT_DIR/.gradle"
fi
mkdir -p "$GRADLE_USER_HOME"
export GRADLE_USER_HOME

# 네이버 키들(지도/검색): 환경에 없으면 ~/.zshrc 의 export 줄을 그대로 가져옴
load_from_zshrc() {
  local var="$1"
  if [ -z "${!var:-}" ] && [ -f "$HOME/.zshrc" ]; then
    local line
    line="$(grep -E "^export ${var}=" "$HOME/.zshrc" | tail -1 || true)"
    [ -n "$line" ] && eval "$line"
  fi
}
load_from_zshrc SMARTPARKING_NAVER_MAP_CLIENT_ID
load_from_zshrc SMARTPARKING_NAVER_SEARCH_CLIENT_ID
load_from_zshrc SMARTPARKING_NAVER_SEARCH_CLIENT_SECRET
load_from_zshrc SMARTPARKING_GEMINI_API_KEY

free_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti:"$port" 2>/dev/null || true)"
  [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
}

cleanup() {
  echo ""
  echo "[run.sh] 종료 중... 서버 정리"
  free_port 8080
  free_port 8000
  exit 0
}
trap cleanup INT TERM

echo "[run.sh] 기존 포트 정리 (8080, 8000)"
free_port 8080
free_port 8000

# --- Spring Boot (백그라운드) ---
if [ -z "${SMARTPARKING_NAVER_MAP_CLIENT_ID:-}" ]; then
  echo "[run.sh] ⚠️  네이버 지도 키가 없습니다. 지도는 안 뜨지만 나머지는 동작합니다."
else
  echo "[run.sh] 네이버 지도 키 적용됨"
fi
echo "[run.sh] Spring Boot 시작 (로그: $SB_LOG)"
(
  cd "$SB_DIR"
  ./gradlew bootRun
) > "$SB_LOG" 2>&1 &

# Spring Boot 기동 대기
echo -n "[run.sh] Spring Boot 기동 대기"
for _ in $(seq 1 90); do
  if grep -q "Started ServerApplication" "$SB_LOG" 2>/dev/null; then
    echo " -> 완료 (http://localhost:8080/)"
    break
  fi
  echo -n "."
  sleep 1
done

# --- FastAPI (포그라운드, 영상 창 표시) ---
echo "[run.sh] FastAPI(YOLO) 시작 - 영상 창이 뜹니다. 종료하려면 Ctrl+C"
cd "$FA_DIR"
SHOW_GUI=1 ./venv/bin/python server0.py
