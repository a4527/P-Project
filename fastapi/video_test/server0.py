import cv2
import json
import numpy as np
import threading
import time
import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from ultralytics import YOLO

app = FastAPI()

# CORS 설정: SpringBoot 및 프론트엔드 통신 허용
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# =====================================================
# 1. 설정 및 경로 (환경에 맞게 수정 가능)
# =====================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
WEIGHTS_PATH = os.path.join(BASE_DIR, "weights", "visDrone.pt")
VIDEO_DIR = os.path.join(BASE_DIR, "videos")
MAP_DIR = os.path.join(BASE_DIR, "map")

YOLO_W, YOLO_H = 854, 480
FRAME_INTERVAL = 30  # 분석 주기 (프레임)
SOURCE_SCAN_INTERVAL = 5.0  # 새 영상/슬롯 파일 재탐색 주기(초)
DEBUG = True         # 실시간 시각화 창 표시 여부

model = YOLO(WEIGHTS_PATH)
vehicle_ids = [3, 4, 5, 9] # car, van, truck, bus 등

# 상태 저장용 글로벌 캐시
status_cache = {"last_update": None}
# 영상/슬롯 파일 자동 로딩 결과
active_sources = []
active_source_signature = None
# 구역별 점유된 슬롯 번호 저장
last_analysis = {}
source_lock = threading.Lock()

# =====================================================
# 2. 데이터 로더 (경로 문제 해결 버전)
# =====================================================
def load_map_data(relative_path):
    # map 디렉터리 기준으로 경로를 해석
    full_path = os.path.join(BASE_DIR, relative_path)
    
    if not os.path.exists(full_path):
        # 만약 못 찾으면 한 단계 상위 폴더에서도 찾아봄 (구조 대비)
        parent_path = os.path.join(os.path.dirname(BASE_DIR), relative_path)
        if os.path.exists(parent_path):
            full_path = parent_path
        else:
            print(f"⚠️ 경고: 파일을 찾을 수 없습니다 -> {full_path}")
            return {}
    
    try:
        with open(full_path, "r", encoding="utf-8") as f:
            raw_data = json.load(f)
        
        parsed_data = {}
        for item in raw_data:
            slot_id = int(item["slot"])
            cx, cy = item["center"]
            w, h = item["w"], item["h"]
            parsed_data[slot_id] = {
                "rect": (int(cx - w/2), int(cy - h/2), int(cx + w/2), int(cy + h/2)),
                "center": [cx, cy],
                "w": w, "h": h,
                "angle": item.get("angle", 0),
                "type": item.get("type", "normal")
            }
        print(f"✅ 로딩 성공: {full_path} ({len(parsed_data)} 슬롯)")
        return parsed_data
    except Exception as e:
        print(f"❌ 로딩 에러: {e}")
        return {}

def discover_video_sources():
    """videos/*.mp4 와 map/*_slots.json 을 파일명 기준으로 자동 매칭한다."""
    sources = []
    if not os.path.exists(VIDEO_DIR):
        print(f"⚠️ 경고: video 디렉터리를 찾을 수 없습니다 -> {VIDEO_DIR}")
        return sources

    for filename in sorted(os.listdir(VIDEO_DIR)):
        if not filename.endswith(".mp4") or "_video" not in filename:
            continue

        key = filename.split("_video", 1)[0]
        if not key:
            continue
        video_path = os.path.join(VIDEO_DIR, filename)
        map_path = os.path.join("map", f"{key}_slots.json")
        map_data = load_map_data(map_path)

        if not map_data:
            print(f"⚠️ 경고: 슬롯 파일이 없어 비디오를 건너뜁니다 -> {key}")
            continue

        sources.append({
            "key": key,
            "video_path": video_path,
            "map_data": map_data,
        })

    return sources

def build_source_signature(sources):
    """파일 변경 여부를 감지하기 위한 서명."""
    signature = []
    for source in sources:
        video_mtime = os.path.getmtime(source["video_path"]) if os.path.exists(source["video_path"]) else None
        slot_path = os.path.join(MAP_DIR, f'{source["key"]}_slots.json')
        slot_mtime = os.path.getmtime(slot_path) if os.path.exists(slot_path) else None
        signature.append((source["key"], video_mtime, slot_mtime))
    return tuple(signature)

def refresh_sources(force=False):
    """파일 추가/변경을 반영해서 자동 로딩 목록을 갱신한다."""
    global active_sources, active_source_signature, last_analysis

    discovered = discover_video_sources()
    signature = build_source_signature(discovered)

    if not force and signature == active_source_signature:
        return

    with source_lock:
        active_sources = discovered
        active_source_signature = signature

        current_keys = {source["key"] for source in discovered}
        for key in list(last_analysis.keys()):
            if key not in current_keys:
                del last_analysis[key]
        for key in current_keys:
            last_analysis.setdefault(key, set())

    print(f"✅ 자동 로딩 완료: {', '.join(sorted(current_keys)) if current_keys else '대상 없음'}")

# =====================================================
# 3. 분석 및 시각화 유틸리티
# =====================================================
def get_rotated_rect_points(cx, cy, w, h, angle_deg):
    angle_rad = np.radians(angle_deg)
    cos_a, sin_a = np.cos(angle_rad), np.sin(angle_rad)
    hw, hh = w / 2, h / 2
    corners = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
    pts = []
    for x, y in corners:
        rx = x * cos_a - y * sin_a
        ry = x * sin_a + y * cos_a
        pts.append([int(cx + rx), int(cy + ry)])
    return np.array(pts, dtype=np.int32)

def draw_realtime(frame, map_data, occupied_set):
    """현재 프레임에 주차 현황 시각화"""
    h, w = frame.shape[:2]
    scale_x, scale_y = w / 854, h / 480 # 빌더 기준 해상도 대응

    for slot_id, data in map_data.items():
        is_occ = slot_id in occupied_set
        stype = data["type"]
        
        # 색상 결정 로직
        if stype == "disabled":
            color = (0, 255, 255) if is_occ else (255, 0, 0) # 노랑(점유) vs 파랑(여유)
        else:
            color = (0, 0, 255) if is_occ else (0, 255, 0)   # 빨강(점유) vs 초록(여유)

        # 회전 사각형 그리기
        pts = get_rotated_rect_points(data["center"][0], data["center"][1], 
                                      data["w"], data["h"], data["angle"])
        # 스케일링 적용
        pts[:, 0] = (pts[:, 0] * scale_x).astype(int)
        pts[:, 1] = (pts[:, 1] * scale_y).astype(int)
        
        cv2.polylines(frame, [pts], True, color, 2)
        cv2.putText(frame, str(slot_id), (int(pts[0][0]), int(pts[0][1] - 5)), 
                    cv2.FONT_HERSHEY_SIMPLEX, 0.4 * scale_x, (255, 255, 255), 1)

# =====================================================
# 4. 백그라운드 분석 워커
# =====================================================
def slot_worker():
    global last_analysis, status_cache
    refresh_sources(force=True)
    caps = {}
    last_scan_time = 0.0
    frame_count = 0

    while True:
        now = time.monotonic()
        if now - last_scan_time >= SOURCE_SCAN_INTERVAL:
            refresh_sources()
            with source_lock:
                current_sources = list(active_sources)
            current_keys = {source["key"] for source in current_sources}
            for key in list(caps.keys()):
                if key not in current_keys:
                    caps[key].release()
                    del caps[key]
            for source in current_sources:
                key = source["key"]
                if key not in caps:
                    caps[key] = cv2.VideoCapture(source["video_path"])
            last_scan_time = now

        with source_lock:
            current_sources = list(active_sources)

        frames = {}
        for source in current_sources:
            key = source["key"]
            cap = caps.get(key)
            if cap is None:
                continue

            ret, frame = cap.read()
            if not ret:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                continue
            
            frames[key] = frame

            # 주기적 분석 실행
            if frame_count % FRAME_INTERVAL == 0:
                small = cv2.resize(frame, (YOLO_W, YOLO_H))
                results = model(small, classes=vehicle_ids, conf=0.35, verbose=False)[0]
                boxes = results.boxes.xyxy.cpu().numpy() if results.boxes is not None else []
                
                occupied_now = set()
                current_map = source["map_data"]
                
                # 객체 중심점이 박스 안에 있는지 판정
                for bx1, by1, bx2, by2 in boxes:
                    bcx, bcy = (bx1 + bx2) / 2, (by1 + by2) / 2
                    for slot_id, data in current_map.items():
                        # 회전 미고려 단순 범위 판정 (필요시 pointPolygonTest로 교체 가능)
                        rx1, ry1, rx2, ry2 = data["rect"]
                        if rx1 <= bcx <= rx2 and ry1 <= bcy <= ry2:
                            occupied_now.add(slot_id)
                
                with source_lock:
                    last_analysis[key] = occupied_now

        # 분석된 데이터를 SpringBoot에 넘길 수 있게 가공
        new_status = {"last_update": time.time()}
        with source_lock:
            status_sources = list(active_sources)
            analysis_snapshot = {key: set(value) for key, value in last_analysis.items()}

        for source in status_sources:
            key = source["key"]
            p_map = source["map_data"]
            occ_set = analysis_snapshot.get(key, set())
            
            slots_info = []
            avail_normal = 0
            avail_disabled = 0
            
            for s_id, data in p_map.items():
                is_occ = s_id in occ_set
                stype = data["type"]
                
                if not is_occ:
                    if stype == "disabled": avail_disabled += 1
                    else: avail_normal += 1
                
                slots_info.append({
                    "slot_id": s_id,
                    "type": stype,
                    "status": "occupied" if is_occ else "available",
                    "center": data["center"]
                })
            
            new_status[key] = {
                "summary": {
                    "total": len(p_map),
                    "available": avail_normal + avail_disabled,
                    "disabled_available": avail_disabled
                },
                "slots": slots_info
            }
        with source_lock:
            status_cache = new_status

        # 디버그 화면 출력
        if DEBUG:
            for key, f in frames.items():
                disp = f.copy()
                source_map_data = next((source["map_data"] for source in status_sources if source["key"] == key), {})
                draw_realtime(disp, source_map_data, analysis_snapshot.get(key, set()))
                cv2.imshow(f"Monitoring - {key}", cv2.resize(disp, (640, 360)))
            if cv2.waitKey(1) & 0xFF == ord('q'): break
            
        frame_count += 1

# 분석 스레드 시작
threading.Thread(target=slot_worker, daemon=True).start()

# =====================================================
# 5. API 엔드포인트
# =====================================================
@app.get("/status")
def get_status():
    """SpringBoot에서 이 데이터를 가져가서 어플에 전달합니다."""
    return status_cache

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
