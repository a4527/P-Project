import cv2
import numpy as np
# from parking_slot_mapping import mapping_parking_slot
from ultralytics import YOLO

# # 1) VisDrone용 YOLOv8 모델 로드
# model = YOLO("weights/visDrone.pt")
# print(model.names)

# # {0: 'pedestrian', 1: 'people', 2: 'bicycle', 3: 'car', 4: 'van', 5: 'truck', 6: 'tricycle', 7: 'awning-tricycle', 8: 'bus', 9: 'motor'}
# # 차량 계열 클래스만 탐지 (car, motorcycle, truck)
# vehicle_ids = [3, 4, 5, 9]  # car, van, truck, motor


# total_slots = 83
# partition1_slots = 41
# partition2_slots = 25
# partition3_slots = 17

# # partition1_available = partition1_slots - partition1_occupied_slots
# # partition2_available = partition2_slots - partition2_occupied_slots
# # partition3_available = partition3_slots - partition3_occupied_slots
# # 이런식으로 구현

import cv2
import json
import numpy as np
from ultralytics import YOLO

# ======================= 설정 =======================

# YOLO 모델 로드
model = YOLO("weights/visDrone.pt")

# VisDrone 클래스에서 차량 계열 클래스 ID
# {0: 'pedestrian', 1: 'people', 2: 'bicycle', 3: 'car',
#  4: 'van', 5: 'truck', 6: 'tricycle', 7: 'awning-tricycle',
#  8: 'bus', 9: 'motor'}
vehicle_ids = [3, 4, 5, 9]  # car, van, truck, motor

# 파티션별 슬롯 개수
total_slots = 83
partition1_slots = 41   # 1~41
partition2_slots = 25   # 42~66
partition3_slots = 17   # 67~83 

# 장애인 주차구역
DISABLED_SLOTS = {45, 46, 65, 66}

# YOLO 검사 주기 (몇 프레임마다 YOLO 돌릴지)
FRAME_INTERVAL = 90  # 30fps 기준이면 약 3초마다 한 번

# YOLO 입력 해상도 (리사이즈 후)
# 필요하면 1280x720으로 올려보기: YOLO_W = 1280, YOLO_H = 720
YOLO_W = 854
YOLO_H = 480

# 비디오 파일 경로
VIDEO_P1 = "videos/partition1_video.mp4"  # 필요하면 "videos/partition1_video.mp4" 이런 식으로 수정
VIDEO_P2 = "videos/partition2_video.mp4"
VIDEO_P3 = "videos/partition3_video.mp4"

# center slots JSON (영상 좌표계, p1/p2 사각형)
CENTER_JSON_P1 = "map/partition1_center_slots.json"      # 1~41
CENTER_JSON_P2 = "map/partition2_center_slots.json"      # 42~66
CENTER_JSON_P3_1 = "map/partition3_1_center_slots.json"  # 67~72 (p2 영상에서 보이는 부분)
CENTER_JSON_P3_2 = "map/partition3_2_center_slots.json"  # 73~83 (p3 영상에서 보이는 부분)

# mapping slots JSON + map 이미지 (맵 색칠용)
MAPPING_JSON = "map/mapping_parking_slot.json"
MAP_IMG_P1 = "map/partition1_map.png"   # 필요하면 "map/partition1_map.png"
MAP_IMG_P2 = "map/partition2_map.png"
MAP_IMG_P3 = "map/partition3_map.png"

# ====================================================


def load_center_slots(json_path):
    """
    center_slots JSON 로드.
    형식 예:
    [
      { "slot": 67, "p1": [x1,y1], "p2": [x2,y2] },
      ...
    ]
    return: {slot: (x1, y1, x2, y2)}  ← 원본 영상 해상도 기준
    """
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    center_slots = {}
    for item in data:
        slot = int(item["slot"])
        x1, y1 = item["p1"]
        x2, y2 = item["p2"]

        left = min(x1, x2)
        right = max(x1, x2)
        top = min(y1, y2)
        bottom = max(y1, y2)

        center_slots[slot] = (left, top, right, bottom)

    return center_slots


def load_mapping_slots(json_path):
    """
    mapping_parking_slot.json 로드.
    형식 예:
    {
      "partition1": [
        {"slot":1,"points":[[...],[...],[...],[...]],"disabled":false},
        ...
      ],
      ...
    }
    return:
    {
      "partition1": {slot: {"points": np.ndarray(4,2), "disabled": bool}, ...},
      ...
    }
    """
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    mapping = {}
    for part_name, slots in data.items():
        mapping[part_name] = {}
        for item in slots:
            slot = int(item["slot"])
            pts = np.array(item["points"], dtype=np.int32)
            disabled = bool(item.get("disabled", False))
            mapping[part_name][slot] = {
                "points": pts,
                "disabled": disabled,
            }
    return mapping


def detect_occupied_slots_scaled(frame, center_rects, conf=0.4):
    """
    frame: 원본(1920x1080) 프레임
    center_rects: {slot: (x1,y1,x2,y2)}  ← 원본 해상도 기준

    1) frame을 (YOLO_W, YOLO_H)로 resize
    2) center_rects도 같은 비율로 scale
    3) YOLO 결과의 bounding box 중심이 scaled rect 안에 들어가면 점유로 판단
    """
    occupied = set()

    if frame is None or len(center_rects) == 0:
        return occupied

    orig_h, orig_w = frame.shape[:2]

    # 1) 프레임 리사이즈
    frame_small = cv2.resize(frame, (YOLO_W, YOLO_H))
    scale_x = YOLO_W / orig_w
    scale_y = YOLO_H / orig_h

    # 2) 슬롯 좌표도 비율에 맞게 스케일링
    scaled_rects = {}
    for slot, (x1, y1, x2, y2) in center_rects.items():
        sx1 = x1 * scale_x
        sy1 = y1 * scale_y
        sx2 = x2 * scale_x
        sy2 = y2 * scale_y
        scaled_rects[slot] = (sx1, sy1, sx2, sy2)

    # 3) YOLO 추론 (리사이즈된 frame_small 기준)
    results = model(
        frame_small,
        classes=vehicle_ids,
        conf=conf,
        imgsz=max(YOLO_W, YOLO_H),
        verbose=False,
    )
    det = results[0]

    if det.boxes is None or len(det.boxes) == 0:
        return occupied

    boxes = det.boxes.xyxy.cpu().numpy()  # (N,4) x1,y1,x2,y2 (small frame 기준)

    for (x1, y1, x2, y2) in boxes:
        cx = (x1 + x2) / 2.0
        cy = (y1 + y2) / 2.0

        for slot, (sx1, sy1, sx2, sy2) in scaled_rects.items():
            if sx1 <= cx <= sx2 and sy1 <= cy <= sy2:
                occupied.add(slot)
                break

    return occupied


def draw_partition_map(base_img, slot_mapping, occupied_slots, partition_name=""):
    """
    맵 이미지에 슬롯 색칠
    base_img: partitionX_map.png
    slot_mapping: {slot: {"points":np.array(4,2), "disabled":bool}}
    occupied_slots: 점유된 slot 번호 set
    """
    vis = base_img.copy()

    for slot, info in slot_mapping.items():
        poly = info["points"].reshape((-1, 1, 2))
        disabled = info["disabled"]

        if slot in occupied_slots:
            color = (0, 0, 255)       # 빨강: 점유
        else:
            if disabled:
                color = (0, 255, 255) # 노랑: 비점유 장애인
            else:
                color = (0, 255, 0)   # 초록: 비점유 일반

        cv2.fillPoly(vis, [poly], color)
        cv2.polylines(vis, [poly], True, (0, 0, 0), 1)

        x_text, y_text = info["points"][0]
        cv2.putText(
            vis,
            str(slot),
            (int(x_text), int(y_text) - 3),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.4,
            (0, 0, 0),
            1,
            cv2.LINE_AA,
        )

    if partition_name:
        cv2.putText(
            vis,
            partition_name,
            (10, 20),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (255, 255, 255),
            2,
            cv2.LINE_AA,
        )

    return vis

def main():
    # ---------- JSON 로드 ----------
    center_p1 = load_center_slots(CENTER_JSON_P1)      # 1~41
    center_p2 = load_center_slots(CENTER_JSON_P2)      # 42~66
    center_p3_1 = load_center_slots(CENTER_JSON_P3_1)  # 67~72 (p2에서 보이는 부분)
    center_p3_2 = load_center_slots(CENTER_JSON_P3_2)  # 73~83 (p3에서 보이는 부분)

    mapping_slots = load_mapping_slots(MAPPING_JSON)

    # ---------- 맵 이미지 ----------
    map_p1_base = cv2.imread(MAP_IMG_P1)
    map_p2_base = cv2.imread(MAP_IMG_P2)
    map_p3_base = cv2.imread(MAP_IMG_P3)

    # ---------- 비디오 ----------
    cap1 = cv2.VideoCapture(VIDEO_P1)
    cap2 = cv2.VideoCapture(VIDEO_P2)
    cap3 = cv2.VideoCapture(VIDEO_P3)

    if not (cap1.isOpened() and cap2.isOpened() and cap3.isOpened()):
        print("비디오 파일을 하나 이상 열 수 없습니다.")
        return

    # ---------- 윈도우 이름 미리 만들기 ----------
    cv2.namedWindow("Partition1 Map", cv2.WINDOW_NORMAL)
    cv2.namedWindow("Partition2 Map", cv2.WINDOW_NORMAL)
    cv2.namedWindow("Partition3 Map", cv2.WINDOW_NORMAL)
    cv2.namedWindow("Partition1 Video", cv2.WINDOW_NORMAL)
    cv2.namedWindow("Partition2 Video", cv2.WINDOW_NORMAL)
    cv2.namedWindow("Partition3 Video", cv2.WINDOW_NORMAL)

    frame_idx = 0
    windows_moved = False   # 한 번만 위치 설정

    while True:
        ret1, frame1 = cap1.read()
        ret2, frame2 = cap2.read()
        ret3, frame3 = cap3.read()

        if not (ret1 and ret2 and ret3):
            print("영상이 끝났거나 프레임을 읽을 수 없습니다. 종료합니다.")
            break

        # ----- 첫 프레임에서 창 위치 2x3 그리드로 배치 -----
        if not windows_moved:
            # 맵 크기
            m1_h, m1_w = map_p1_base.shape[:2]
            m2_h, m2_w = map_p2_base.shape[:2]
            m3_h, m3_w = map_p3_base.shape[:2]

            # 아래줄 y좌표는 맵들 중 가장 높은 높이 + margin
            margin = 30
            y_maps = 0
            y_videos = max(m1_h, m2_h, m3_h) + margin

            # x 좌표: 왼쪽부터 쭉 이어서
            x1 = 0
            x2 = m1_w + margin
            x3 = m1_w + m2_w + 2 * margin

            # 맵 윈도우 위치
            cv2.moveWindow("Partition1 Map", x1, y_maps)
            cv2.moveWindow("Partition2 Map", x2, y_maps)
            cv2.moveWindow("Partition3 Map", x3, y_maps)

            # 비디오 윈도우 위치 (맵 바로 아래)
            cv2.moveWindow("Partition1 Video", x1, y_videos)
            cv2.moveWindow("Partition2 Video", x2, y_videos)
            cv2.moveWindow("Partition3 Video", x3, y_videos)

            windows_moved = True

        # ---------- 원본 영상 표시 ----------
        cv2.imshow("Partition1 Video", frame1)
        cv2.imshow("Partition2 Video", frame2)
        cv2.imshow("Partition3 Video", frame3)

        # 일정 프레임마다만 YOLO 검사
        if frame_idx % FRAME_INTERVAL == 0:
            # 1) 각 partition에서 점유된 슬롯 계산
            occ_p1 = detect_occupied_slots_scaled(frame1, center_p1)
            occ_p2 = detect_occupied_slots_scaled(frame2, center_p2)
            occ_p3_1 = detect_occupied_slots_scaled(frame2, center_p3_1)
            occ_p3_2 = detect_occupied_slots_scaled(frame3, center_p3_2)
            occ_p3 = occ_p3_1.union(occ_p3_2)

            # 2) 잔여 계산
            p1_occupied = len(occ_p1)
            p2_occupied = len(occ_p2)
            p3_occupied = len(occ_p3)

            p1_available = partition1_slots - p1_occupied
            p2_available = partition2_slots - p2_occupied
            p3_available = partition3_slots - p3_occupied
            total_occupied = p1_occupied + p2_occupied + p3_occupied
            total_available = total_slots - total_occupied

            print(f"[Frame {frame_idx}]")
            print(f"  Partition1: occupied={p1_occupied}, available={p1_available}")
            print(f"  Partition2: occupied={p2_occupied}, available={p2_available}")
            print(f"  Partition3: occupied={p3_occupied}, available={p3_available}")
            print(f"  Total: occupied={total_occupied}, available={total_available}")
            print("-" * 60)

            # 3) 맵 색칠
            map_p1_vis = draw_partition_map(
                map_p1_base,
                mapping_slots["partition1"],
                occ_p1,
                partition_name=f"P1 avail: {p1_available}",
            )
            map_p2_vis = draw_partition_map(
                map_p2_base,
                mapping_slots["partition2"],
                occ_p2,
                partition_name=f"P2 avail: {p2_available}",
            )
            map_p3_vis = draw_partition_map(
                map_p3_base,
                mapping_slots["partition3"],
                occ_p3,
                partition_name=f"P3 avail: {p3_available}",
            )

            cv2.imshow("Partition1 Map", map_p1_vis)
            cv2.imshow("Partition2 Map", map_p2_vis)
            cv2.imshow("Partition3 Map", map_p3_vis)

        frame_idx += 1

        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

    cap1.release()
    cap2.release()
    cap3.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
