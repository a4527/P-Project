import cv2
import numpy as np
import json
import os
import math

# ================= 1. 경로 설정 =================
CURRENT_FILE_PATH = os.path.abspath(__file__)
MAPBUILDER_DIR = os.path.dirname(CURRENT_FILE_PATH)
PROJECT_ROOT = os.path.dirname(MAPBUILDER_DIR)

VIDEO_TEST_DIR = os.path.join(PROJECT_ROOT, "video_test")
IMAGE_FOLDER = os.path.join(VIDEO_TEST_DIR, "images")
MAP_FOLDER = os.path.join(VIDEO_TEST_DIR, "map")

os.makedirs(MAP_FOLDER, exist_ok=True)

target_name = input("작업할 파티션 이름을 입력하세요 (예: partition1): ").strip()

INPUT_IMAGE_PATH = os.path.join(IMAGE_FOLDER, f"{target_name}_image.png")
OUTPUT_JSON_PATH = os.path.join(MAP_FOLDER, f"custom_{target_name}_slots.json")
OUTPUT_MAP_PATH = os.path.join(MAP_FOLDER, f"custom_{target_name}_map.png")

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
        else:
            # 2. 빈 공간 클릭 시 새 슬롯 추가
            slots.append({"cx": x, "cy": y, "w": 40, "h": 70, "angle": 0, "type": "normal"})
            selected_idx = len(slots) - 1
            dragging = True
            is_new_slot = True # 방금 생성됨

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
    print("💡 딸깍 클릭: 일반/장애인석 토글 | 드래그: 위치 이동 | 's': 저장 후 종료")

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
            output_data = []
            for i, s in enumerate(slots):
                output_data.append({
                    "slot": i + 1, 
                    "type": s.get('type', 'normal'), # type 정보 포함
                    "center": [float(s['cx']), float(s['cy'])],
                    "w": s['w'], "h": s['h'], "angle": s['angle']
                })
            
            with open(OUTPUT_JSON_PATH, "w", encoding="utf-8") as f:
                json.dump(output_data, f, indent=4)
            
            cv2.imwrite(OUTPUT_MAP_PATH, canvas)
            print(f"\n✅ [{target_name}] 저장 완료 및 종료!")
            break

    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()