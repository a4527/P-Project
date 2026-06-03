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
DEBUG = True         # 실시간 시각화 창 표시 여부

model = YOLO(WEIGHTS_PATH)
vehicle_ids = [3, 4, 5, 9] # car, van, truck, bus 등

VIDEO_SOURCES = [
    ("P1", os.path.join(VIDEO_DIR, "partition1_video.mp4")),
    ("P2", os.path.join(VIDEO_DIR, "partition2_video.mp4")),
    ("P3", os.path.join(VIDEO_DIR, "partition3_video.mp4")),
]

# 상태 저장용 글로벌 캐시
status_cache = {"last_update": None}
# 구역별 점유된 슬롯 번호 저장
last_analysis = {"P1": set(), "P2": set(), "P3": set()}

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

# 파일명과 폴더 구조에 맞춰 경로 수정 (가장 많이 쓰는 구조 2가지 적용)
MAP_DATA = {
    "P1": load_map_data(os.path.join("map", "custom_partition1_slots.json")),
    "P2": load_map_data(os.path.join("map", "custom_partition2_slots.json")),
    "P3": load_map_data(os.path.join("map", "custom_partition3_slots.json")),
}

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

def draw_realtime(frame, partition_id, occupied_set):
    """현재 프레임에 주차 현황 시각화"""
    slots_dict = MAP_DATA.get(partition_id, {})
    h, w = frame.shape[:2]
    scale_x, scale_y = w / 854, h / 480 # 빌더 기준 해상도 대응

    for slot_id, data in slots_dict.items():
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
    caps = {name: cv2.VideoCapture(path) for name, path in VIDEO_SOURCES}
    frame_count = 0

    while True:
        frames = {}
        for name, cap in caps.items():
            ret, frame = cap.read()
            if not ret:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                continue
            
            frames[name] = frame

            # 주기적 분석 실행
            if frame_count % FRAME_INTERVAL == 0:
                small = cv2.resize(frame, (YOLO_W, YOLO_H))
                results = model(small, classes=vehicle_ids, conf=0.35, verbose=False)[0]
                boxes = results.boxes.xyxy.cpu().numpy() if results.boxes is not None else []
                
                occupied_now = set()
                current_map = MAP_DATA.get(name, {})
                
                # 객체 중심점이 박스 안에 있는지 판정
                for bx1, by1, bx2, by2 in boxes:
                    bcx, bcy = (bx1 + bx2) / 2, (by1 + by2) / 2
                    for slot_id, data in current_map.items():
                        # 회전 미고려 단순 범위 판정 (필요시 pointPolygonTest로 교체 가능)
                        rx1, ry1, rx2, ry2 = data["rect"]
                        if rx1 <= bcx <= rx2 and ry1 <= bcy <= ry2:
                            occupied_now.add(slot_id)
                
                last_analysis[name] = occupied_now

        # 분석된 데이터를 SpringBoot에 넘길 수 있게 가공
        new_status = {"last_update": time.time()}
        for p_id in ["P1", "P2", "P3"]:
            p_map = MAP_DATA.get(p_id, {})
            occ_set = last_analysis.get(p_id, set())
            
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
            
            new_status[p_id] = {
                "summary": {
                    "total": len(p_map),
                    "available": avail_normal + avail_disabled,
                    "disabled_available": avail_disabled
                },
                "slots": slots_info
            }
        status_cache = new_status

        # 디버그 화면 출력
        if DEBUG:
            for name, f in frames.items():
                disp = f.copy()
                draw_realtime(disp, name, last_analysis[name])
                cv2.imshow(f"Monitoring - {name}", cv2.resize(disp, (640, 360)))
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
