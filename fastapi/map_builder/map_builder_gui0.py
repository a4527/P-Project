import cv2
import numpy as np
import json
import os
import math
import sys
import argparse
from dataclasses import replace

from auto_slot_generator import (
    estimate_meters_per_pixel_from_image,
    generate_auto_slots,
    load_auto_spec,
)

# ================= 1. 경로 설정 =================
CURRENT_FILE_PATH = os.path.abspath(__file__)
MAPBUILDER_DIR = os.path.dirname(CURRENT_FILE_PATH)
PROJECT_ROOT = os.path.dirname(MAPBUILDER_DIR)

VIDEO_TEST_DIR = os.path.join(PROJECT_ROOT, "video_test")
IMAGE_FOLDER = os.path.join(VIDEO_TEST_DIR, "images")
MAP_FOLDER = os.path.join(VIDEO_TEST_DIR, "map")

os.makedirs(MAP_FOLDER, exist_ok=True)

def parse_args():
    parser = argparse.ArgumentParser(description="Parking map builder")
    parser.add_argument("target_name", nargs="?", help="map identifier")
    parser.add_argument("--auto-spec", dest="auto_spec", help="polygon spec json path")
    parser.add_argument("--force-auto", action="store_true", help="regenerate slots even if a slot json already exists")
    parser.add_argument("--save-only", action="store_true", help="save generated result and exit without opening the GUI")
    return parser.parse_args()


ARGS = parse_args()

if ARGS.target_name and ARGS.target_name.strip():
    target_name = ARGS.target_name.strip()
else:
    target_name = input("작업할 맵 식별자를 입력하세요 (예: gachon_ai, gachon_library): ").strip()

INPUT_IMAGE_PATH = os.path.join(IMAGE_FOLDER, f"{target_name}_image.png")
OUTPUT_JSON_PATH = os.path.join(MAP_FOLDER, f"{target_name}_slots.json")
OUTPUT_MAP_PATH = os.path.join(MAP_FOLDER, f"{target_name}_map.png")
DEFAULT_AUTO_SPEC_CANDIDATES = [
    ARGS.auto_spec,
    os.path.join(MAP_FOLDER, f"{target_name}_auto_spec.json"),
    os.path.join(MAP_FOLDER, f"{target_name}_polygon.json"),
    os.path.join(IMAGE_FOLDER, f"{target_name}_polygon.json"),
]

CANVAS_W, CANVAS_H = 854, 480

if not os.path.exists(INPUT_IMAGE_PATH):
    print(f"❌ 파일 없음: {INPUT_IMAGE_PATH}")
    exit()

img_src = cv2.imread(INPUT_IMAGE_PATH)
background_img = cv2.resize(img_src, (CANVAS_W, CANVAS_H))

# ================= 2. 전역 변수 및 함수 =================
slots = []
selected_idx = -1
dragging = False
is_moved = False  # 드래그 여부 판별용
start_x, start_y = -1, -1
is_new_slot = False
auto_generation_info = None


def load_existing_slots():
    if not os.path.exists(OUTPUT_JSON_PATH):
        return []

    try:
        with open(OUTPUT_JSON_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)

        loaded_slots = []
        for item in data:
            center = item.get("center", [0, 0])
            loaded_slots.append({
                "cx": int(center[0]),
                "cy": int(center[1]),
                "w": int(item.get("w", 40)),
                "h": int(item.get("h", 70)),
                "angle": int(item.get("angle", 0)),
                "type": item.get("type", "normal")
            })

        print(f"♻️ 기존 슬롯 로드 완료: {OUTPUT_JSON_PATH} ({len(loaded_slots)}개)")
        return loaded_slots
    except Exception as error:
        print(f"⚠️ 기존 슬롯 로드 실패: {error}")
        return []


slots = load_existing_slots()


def resolve_auto_spec_path():
    for candidate in DEFAULT_AUTO_SPEC_CANDIDATES:
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def persist_estimated_scale(auto_spec_path, estimated_scale):
    try:
        with open(auto_spec_path, "r", encoding="utf-8") as f:
            raw = json.load(f)
        raw["meters_per_pixel"] = estimated_scale
        with open(auto_spec_path, "w", encoding="utf-8") as f:
            json.dump(raw, f, indent=4)
    except Exception as error:
        print(f"⚠️ 자동 추정 스케일 저장 실패: {error}")


def search_best_scale(spec):
    base_scale = float(spec.meters_per_pixel)
    candidate_scales = {
        base_scale,
        0.03,
        0.04,
        0.05,
        0.06,
        0.07,
        0.08,
        0.09,
        0.10,
        0.11,
        0.12,
    }
    for factor in (0.6, 0.75, 0.9, 1.1, 1.25, 1.5, 1.75):
        candidate_scales.add(round(base_scale * factor, 4))

    best_result = None
    best_scale = base_scale
    for candidate_scale in sorted(scale for scale in candidate_scales if scale and scale > 0):
        candidate_spec = replace(spec, meters_per_pixel=float(candidate_scale))
        result = generate_auto_slots(background_img.shape, candidate_spec)
        if not result["slots"]:
            continue

        if best_result is None:
            best_result = result
            best_scale = float(candidate_scale)
            continue

        current_score = (len(result["slots"]), float(result["summary"].get("coverage", 0.0)))
        best_score = (len(best_result["slots"]), float(best_result["summary"].get("coverage", 0.0)))
        if current_score > best_score:
            best_result = result
            best_scale = float(candidate_scale)

    return best_result, best_scale


def export_slots():
    output_data = []
    for i, s in enumerate(slots):
        output_data.append({
            "slot": i + 1,
            "type": s.get("type", "normal"),
            "center": [float(s["cx"]), float(s["cy"])],
            "w": int(s["w"]),
            "h": int(s["h"]),
            "angle": int(s["angle"]),
        })

    with open(OUTPUT_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(output_data, f, indent=4)

    cv2.imwrite(OUTPUT_MAP_PATH, background_img.copy() if not slots else render_canvas())


def try_auto_generate_slots():
    global slots, selected_idx, auto_generation_info
    auto_spec_path = resolve_auto_spec_path()
    if not auto_spec_path:
        return
    if slots and not ARGS.force_auto:
        print(f"ℹ️ 자동 생성 스펙 감지: {auto_spec_path} (기존 슬롯이 있어 수동 편집을 유지합니다)")
        return

    try:
        spec = load_auto_spec(auto_spec_path)
        estimated_scale = estimate_meters_per_pixel_from_image(INPUT_IMAGE_PATH)
        if estimated_scale is not None:
            spec = replace(spec, meters_per_pixel=estimated_scale)
            persist_estimated_scale(auto_spec_path, estimated_scale)
            print(f"🔎 차량 기준 자동 보정: meters_per_pixel={estimated_scale:.4f}")
        result = generate_auto_slots(background_img.shape, spec)
        fallback_result, fallback_scale = search_best_scale(spec)
        if fallback_result is not None and len(fallback_result["slots"]) > len(result["slots"]):
            result = fallback_result
            spec = replace(spec, meters_per_pixel=fallback_scale)
            persist_estimated_scale(auto_spec_path, fallback_scale)
            print(f"🔁 스케일 재탐색 성공: meters_per_pixel={fallback_scale:.4f}")
    except Exception as error:
        print(f"⚠️ 자동 슬롯 생성 실패: {error}")
        return

    slots = []
    for item in result["slots"]:
        center = item.get("center", [0, 0])
        slots.append({
            "cx": int(round(center[0])),
            "cy": int(round(center[1])),
            "w": int(item.get("w", 40)),
            "h": int(item.get("h", 70)),
            "angle": int(item.get("angle", 0)),
            "type": item.get("type", "normal"),
        })
    selected_idx = -1
    auto_generation_info = result
    guide_count = len(result.get("guides", [])) if isinstance(result.get("guides"), list) else 0
    selected_angle_label = result.get("selected_angle")
    if isinstance(selected_angle_label, (int, float)):
        selected_angle_text = f"{selected_angle_label}°"
    else:
        selected_angle_text = str(selected_angle_label)
    if slots:
        print(f"🤖 자동 슬롯 생성 완료: {len(slots)}개, 선택 각도 {selected_angle_text}, 스펙 {auto_spec_path}")
    else:
        print(f"🤖 기준선 생성 완료: {guide_count}개, 스펙 {auto_spec_path}")
    if ARGS.save_only:
        print("💾 --save-only 옵션으로 바로 저장합니다.")
        save_and_exit()


def render_canvas():
    canvas = background_img.copy()

    guides = []
    if auto_generation_info and isinstance(auto_generation_info.get("guides"), list):
        guides = auto_generation_info["guides"]

    for guide in guides:
        line = guide.get("line") or []
        if len(line) < 2:
            continue
        p1 = tuple(int(round(coord)) for coord in line[0][:2])
        p2 = tuple(int(round(coord)) for coord in line[1][:2])
        cv2.line(canvas, p1, p2, (0, 255, 255), 3)
        cv2.circle(canvas, p1, 5, (0, 255, 255), -1)
        cv2.circle(canvas, p2, 5, (0, 255, 255), -1)
        mid_x = int(round((p1[0] + p2[0]) / 2))
        mid_y = int(round((p1[1] + p2[1]) / 2))
        label = f"G{int(guide.get('area_index', 0))}"
        cv2.putText(canvas, label, (mid_x + 6, mid_y - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)

    for i, slot in enumerate(slots):
        pts = get_rotated_rect_points(slot['cx'], slot['cy'], slot['w'], slot['h'], slot['angle'])

        if slot.get('type') == "disabled":
            color = (255, 0, 0)
        else:
            color = (0, 255, 0) if i == selected_idx else (0, 0, 255)

        cv2.polylines(canvas, [pts], True, color, 2)
        cv2.putText(canvas, str(i + 1), (slot['cx'] - 10, slot['cy'] + 5),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
    return canvas


def save_and_exit():
    canvas = render_canvas()
    export_data = []
    for i, s in enumerate(slots):
        export_data.append({
            "slot": i + 1,
            "type": s.get('type', 'normal'),
            "center": [float(s['cx']), float(s['cy'])],
            "w": int(s['w']),
            "h": int(s['h']),
            "angle": int(s['angle']),
        })

    with open(OUTPUT_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(export_data, f, indent=4)

    cv2.imwrite(OUTPUT_MAP_PATH, canvas)
    print(f"\n✅ [{target_name}] 저장 완료 및 종료!")
    cv2.destroyAllWindows()
    sys.exit(0)

def get_rotated_rect_points(cx, cy, w, h, angle_deg):
    angle_rad = math.radians(angle_deg)
    cos_a, sin_a = math.cos(angle_rad), math.sin(angle_rad)
    hw, hh = w / 2, h / 2
    corners = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
    rotated_corners = []
    for x, y in corners:
        rx = x * cos_a - y * sin_a
        ry = x * sin_a + y * cos_a
        rotated_corners.append([int(cx + rx), int(cy + ry)])
    return np.array(rotated_corners, dtype=np.int32)


try_auto_generate_slots()


def mouse_callback(event, x, y, flags, param):
    global slots, selected_idx, dragging, is_moved, start_x, start_y, is_new_slot

    if event == cv2.EVENT_LBUTTONDOWN:
        start_x, start_y = x, y
        is_moved = False
        is_new_slot = False
        clicked_idx = -1
        
        # 1. 기존 박스 클릭 확인 (역순으로 체크해야 가장 위에 있는 박스 선택)
        for i in range(len(slots)-1, -1, -1):
            pts = get_rotated_rect_points(slots[i]['cx'], slots[i]['cy'], slots[i]['w'], slots[i]['h'], slots[i]['angle'])
            if cv2.pointPolygonTest(pts, (x, y), False) >= 0:
                clicked_idx = i
                break
        
        if clicked_idx != -1:
            selected_idx = clicked_idx
            dragging = True  # 기존 박스를 잡았으므로 드래그 시작
            cv2.setTrackbarPos("Angle", "Parking Map Builder", slots[selected_idx]["angle"])
            cv2.setTrackbarPos("Width", "Parking Map Builder", slots[selected_idx]["w"])
            cv2.setTrackbarPos("Height", "Parking Map Builder", slots[selected_idx]["h"])
        else:
            # 2. 빈 공간 클릭 시 새 슬롯 추가
            default_slot = {"w": 40, "h": 70, "angle": 0, "type": "normal"}
            source_slot = slots[selected_idx] if selected_idx != -1 and 0 <= selected_idx < len(slots) else None
            new_slot = {
                "cx": x,
                "cy": y,
                "w": int(source_slot.get("w", default_slot["w"])) if source_slot else default_slot["w"],
                "h": int(source_slot.get("h", default_slot["h"])) if source_slot else default_slot["h"],
                "angle": int(source_slot.get("angle", default_slot["angle"])) if source_slot else default_slot["angle"],
                "type": source_slot.get("type", default_slot["type"]) if source_slot else default_slot["type"],
            }
            slots.append(new_slot)
            selected_idx = len(slots) - 1
            dragging = True
            is_new_slot = True # 방금 생성됨
            cv2.setTrackbarPos("Angle", "Parking Map Builder", new_slot["angle"])
            cv2.setTrackbarPos("Width", "Parking Map Builder", new_slot["w"])
            cv2.setTrackbarPos("Height", "Parking Map Builder", new_slot["h"])

    elif event == cv2.EVENT_MOUSEMOVE:
        if dragging and selected_idx != -1:
            # 드래그 거리 측정
            if abs(x - start_x) > 2 or abs(y - start_y) > 2:
                is_moved = True
            
            # [복구] 이동 로직: 현재 선택된 슬롯의 중심 좌표 업데이트
            slots[selected_idx]['cx'] = x
            slots[selected_idx]['cy'] = y

    elif event == cv2.EVENT_LBUTTONUP:
        # [조건] 
        # 1. 드래그(이동)하지 않았어야 함
        # 2. 이번 클릭에 새로 만든 박스가 아니어야 함
        # 3. 박스를 정확히 클릭했어야 함
        if not is_moved and not is_new_slot and selected_idx != -1:
            slots[selected_idx]['type'] = "disabled" if slots[selected_idx].get('type') == "normal" else "normal"
            print(f"🔄 Slot {selected_idx + 1} 타입 변경")
        
        dragging = False
        # 아래 플래그들은 다음 클릭을 위해 리셋
        is_moved = False
        is_new_slot = False

# ================= 3. 메인 루프 =================
def main():
    global slots, selected_idx
    win_name = "Parking Map Builder"
    
    cv2.namedWindow(win_name, cv2.WINDOW_GUI_EXPANDED) 
    cv2.setMouseCallback(win_name, mouse_callback)

    cv2.createTrackbar("Angle", win_name, 0, 360, lambda x: None)
    cv2.createTrackbar("Width", win_name, 40, 200, lambda x: None)
    cv2.createTrackbar("Height", win_name, 70, 200, lambda x: None)

    print(f"🚀 {target_name} 작업 중...")
    print("💡 딸깍 클릭: 일반/장애인석 토글 | 드래그: 위치 이동")
    print("⌨️ 단축키: s=저장 후 종료 | d=선택 슬롯 삭제 | q=종료")

    while True:
        canvas = background_img.copy()
        
        if selected_idx != -1:
            # 트랙바 값을 현재 선택된 슬롯에 적용
            slots[selected_idx]['angle'] = cv2.getTrackbarPos("Angle", win_name)
            slots[selected_idx]['w'] = max(5, cv2.getTrackbarPos("Width", win_name))
            slots[selected_idx]['h'] = max(5, cv2.getTrackbarPos("Height", win_name))

        for i, slot in enumerate(slots):
            pts = get_rotated_rect_points(slot['cx'], slot['cy'], slot['w'], slot['h'], slot['angle'])
            
            # [핵심] 타입에 따른 색상 구분 (장애인: 파랑, 일반: 초록/빨강)
            if slot.get('type') == "disabled":
                color = (255, 0, 0) # 파란색 (BGR)
            else:
                color = (0, 255, 0) if i == selected_idx else (0, 0, 255)
            
            cv2.polylines(canvas, [pts], True, color, 2)
            
            # 슬롯 번호 표시
            cv2.putText(canvas, str(i+1), (slot['cx']-10, slot['cy']+5), 
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

        cv2.imshow(win_name, canvas)
        key = cv2.waitKey(1) & 0xFF
        
        if key == ord('q'): 
            break
        elif key == ord('d') and selected_idx != -1:
            del slots[selected_idx]
            selected_idx = -1
        elif key == ord('s'):
            save_and_exit()

    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
