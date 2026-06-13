import json
import math
import os
from collections import deque
from dataclasses import dataclass
from typing import Iterable, List, Optional, Sequence, Tuple

import cv2
import numpy as np

Point = Tuple[float, float]


@dataclass(frozen=True)
class AutoSlotSpec:
    areas: List[List[Point]]
    obstacles: List[List[Point]]
    entrances: List[List[Point]]
    meters_per_pixel: float
    slot_width_m: float = 2.5
    slot_length_m: float = 5.0
    lane_width_m: float = 6.0
    angles: Tuple[float, ...] = tuple(float(angle) for angle in range(0, 180, 5))
    search_samples: int = 4
    edge_clearance_px: int = 2


def load_auto_spec(path: str) -> AutoSlotSpec:
    with open(path, "r", encoding="utf-8") as f:
        raw = json.load(f)

    areas = _parse_areas(raw)
    if not areas:
        raise ValueError("areas must contain at least one polygon")

    obstacles = _parse_obstacles(raw)
    entrances = _parse_entrances(raw)
    if not entrances:
        legacy_entrance = _parse_entrance(raw.get("entrance") or raw.get("entrance_point") or raw.get("entry"))
        if legacy_entrance:
            entrances = [legacy_entrance for _ in areas]

    meters_per_pixel = float(
        raw.get("meters_per_pixel")
        or raw.get("scale")
        or raw.get("resolution_m_per_px")
        or 0.05
    )
    if meters_per_pixel <= 0:
        raise ValueError("meters_per_pixel must be greater than 0")

    angles = raw.get("angles") or raw.get("orientations")
    if not angles:
        angle_tuple = tuple(float(angle) for angle in range(0, 180, 5))
    else:
        angle_values = [float(angle) for angle in angles]
        legacy_default = [0.0, 45.0, 90.0]
        if len(angle_values) <= 3 and angle_values == legacy_default[: len(angle_values)]:
            angle_tuple = tuple(float(angle) for angle in range(0, 180, 5))
        else:
            angle_tuple = tuple(angle_values)

    return AutoSlotSpec(
        areas=areas,
        obstacles=obstacles,
        entrances=entrances,
        meters_per_pixel=meters_per_pixel,
        slot_width_m=float(raw.get("slot_width_m", 2.5)),
        slot_length_m=float(raw.get("slot_length_m", 5.0)),
        lane_width_m=float(raw.get("lane_width_m", 6.0)),
        angles=angle_tuple,
        search_samples=max(1, int(raw.get("search_samples", 4))),
        edge_clearance_px=max(1, int(raw.get("edge_clearance_px", 2))),
    )


def estimate_meters_per_pixel_from_image(image_path: str, weights_path: Optional[str] = None) -> Optional[float]:
    if not os.path.exists(image_path):
        return None

    resolved_weights = weights_path or _default_weights_path()
    if not resolved_weights or not os.path.exists(resolved_weights):
        return None

    try:
        from ultralytics import YOLO
    except Exception:
        return None

    try:
        model = YOLO(resolved_weights)
        results = model(image_path, classes=[3, 4, 5, 9], conf=0.25, verbose=False)[0]
        boxes = results.boxes.xyxy.cpu().numpy() if results.boxes is not None else []
    except Exception:
        return None

    short_sides = []
    long_sides = []
    for x1, y1, x2, y2 in boxes:
        width = abs(float(x2) - float(x1))
        height = abs(float(y2) - float(y1))
        short_side = min(width, height)
        long_side = max(width, height)
        if short_side >= 6 and long_side >= 8:
            short_sides.append(short_side)
            long_sides.append(long_side)

    if not short_sides or not long_sides:
        return None

    median_short_side = float(np.median(short_sides))
    median_long_side = float(np.median(long_sides))
    if median_short_side <= 0 or median_long_side <= 0:
        return None

    estimated_from_width = 1.9 / median_short_side
    estimated_from_length = 4.5 / median_long_side
    estimated = max(estimated_from_width, estimated_from_length) * 1.05
    return float(min(max(estimated, 0.01), 0.25))


def generate_auto_slots(image_shape: Sequence[int], spec: AutoSlotSpec) -> dict:
    if len(image_shape) < 2:
        raise ValueError("image_shape must include height and width")

    canvas_h, canvas_w = int(image_shape[0]), int(image_shape[1])
    origin = (canvas_w / 2.0, canvas_h / 2.0)

    slot_w = max(1, int(round(spec.slot_width_m / spec.meters_per_pixel)))
    slot_h = max(1, int(round(spec.slot_length_m / spec.meters_per_pixel)))

    guide_lines = []
    all_slots = []
    for area_index, area in enumerate(spec.areas, start=1):
        area_entrance = spec.entrances[area_index - 1] if area_index - 1 < len(spec.entrances) else []
        if not area_entrance:
            area_entrance = [_polygon_centroid(area)]

        area_result = _generate_single_row_candidates_for_area(
            area=area,
            obstacles=spec.obstacles,
            entrance_points=area_entrance,
            origin=origin,
            slot_w=slot_w,
            slot_h=slot_h,
            edge_clearance_px=spec.edge_clearance_px,
            canvas_shape=(canvas_h, canvas_w),
        )
        if area_result.get("guide_line") is not None:
            guide_lines.append(
                {
                    "area_index": area_index,
                    "line": area_result["guide_line"],
                    "angle": float(area_result["angle"]),
                }
            )
        for slot in area_result.get("slots", []):
            slot["area_index"] = area_index
            all_slots.append(slot)

    return {
        "slots": [
            {
                "slot": index,
                "type": "normal",
                "center": [float(slot["cx"]), float(slot["cy"])],
                "w": int(slot["w"]),
                "h": int(slot["h"]),
                "angle": int(round(slot["angle"])),
            }
            for index, slot in enumerate(all_slots, start=1)
        ],
        "selected_angle": None if not all_slots else "mixed",
        "guides": guide_lines,
        "summary": {
            "total": len(all_slots),
            "available": len(all_slots),
            "disabled_available": 0,
            "coverage": float(sum(int(slot["w"]) * int(slot["h"]) for slot in all_slots)) / float(max(1, canvas_w * canvas_h)),
        },
    }


def _generate_single_row_candidates_for_area(
    *,
    area: Sequence[Point],
    obstacles: Sequence[Sequence[Point]],
    entrance_points: Sequence[Point],
    origin: Point,
    slot_w: int,
    slot_h: int,
    edge_clearance_px: int,
    canvas_shape: Tuple[int, int],
)-> dict:
    reference_edge = _select_reference_edge(area, entrance_points)
    if reference_edge is None:
        return {"slots": [], "guide_line": None, "angle": 0.0}

    (x1, y1), (x2, y2) = reference_edge
    edge_angle = math.degrees(math.atan2(float(y2 - y1), float(x2 - x1))) % 180.0
    rotated_area = _rotate_polygon(area, origin, -edge_angle)
    rotated_obstacles = [_rotate_polygon(obstacle, origin, -edge_angle) for obstacle in obstacles if len(obstacle) >= 3]
    rotated_entrances = [_rotate_point(point, origin, -edge_angle) for point in entrance_points if point is not None]
    rotated_edge = [_rotate_point(point, origin, -edge_angle) for point in reference_edge]

    shifted_points = list(rotated_area)
    for obstacle in rotated_obstacles:
        shifted_points.extend(obstacle)
    shifted_points.extend(rotated_entrances)
    shifted_points.extend(rotated_edge)
    if not shifted_points:
        return {"slots": [], "guide_line": None, "angle": edge_angle}

    shift_x, shift_y, _, _ = _build_padding_shift(shifted_points)
    shifted_area = [_shift_point(point, shift_x, shift_y) for point in rotated_area]
    canvas_h, canvas_w = canvas_shape
    free_mask = _build_free_mask((canvas_h, canvas_w), [rotated_area], rotated_obstacles, shift_x, shift_y)
    if not np.any(free_mask):
        return {"slots": [], "guide_line": None, "angle": edge_angle}

    shifted_entrances = [_shift_point(point, shift_x, shift_y) for point in rotated_entrances]
    reachable_mask = _build_reachable_mask(free_mask, shifted_entrances)
    if reachable_mask is None:
        fallback_entrance = _shift_point(_rotate_point(_polygon_centroid(area), origin, -edge_angle), shift_x, shift_y)
        reachable_mask = _build_reachable_mask(free_mask, [fallback_entrance])
        if reachable_mask is None:
            return {"slots": [], "guide_line": None, "angle": edge_angle}

    shifted_edge = [_shift_point(point, shift_x, shift_y) for point in rotated_edge]
    edge_left, edge_right = sorted(shifted_edge, key=lambda point: point[0])
    line_min_x = float(edge_left[0])
    line_max_x = float(edge_right[0])
    guide_y = float((shifted_edge[0][1] + shifted_edge[1][1]) / 2.0)
    centroid_rotated = _shift_point(_rotate_point(_polygon_centroid(area), origin, -edge_angle), shift_x, shift_y)
    inward_sign = 1.0 if centroid_rotated[1] > guide_y else -1.0
    slot_center_y = guide_y + inward_sign * (slot_h * 0.78 + edge_clearance_px * 2.5)
    slot_gap_px = max(edge_clearance_px * 3, max(8, int(round(slot_w * 0.5))))
    slot_angle = edge_angle

    slots = []
    x_cursor = line_min_x + slot_w / 2.0
    x_end = line_max_x - slot_w / 2.0
    while x_cursor <= x_end:
        center_rotated = (x_cursor, slot_center_y)
        x = int(round(center_rotated[0] - slot_w / 2.0))
        y = int(round(center_rotated[1] - slot_h / 2.0))
        if _rect_within_bounds(x, y, slot_w, slot_h, canvas_w, canvas_h) and _rect_is_free(
            free_mask, x, y, slot_w, slot_h
        ) and _rect_has_access(
            free_mask,
            reachable_mask,
            x,
            y,
            slot_w,
            slot_h,
            edge_clearance_px,
        ):
            center_original = _rotate_point(center_rotated, origin, edge_angle)
            access_point = _rect_access_point(
                free_mask,
                reachable_mask,
                x,
                y,
                slot_w,
                slot_h,
                edge_clearance_px,
            )
            slots.append(
                {
                    "cx": center_original[0],
                    "cy": center_original[1],
                    "w": slot_w,
                    "h": slot_h,
                    "angle": slot_angle,
                    "access_point": access_point,
                }
            )
        x_cursor += slot_w + slot_gap_px

    guide_line = [[float(reference_edge[0][0]), float(reference_edge[0][1])], [float(reference_edge[1][0]), float(reference_edge[1][1])]]
    return {"slots": slots, "guide_line": guide_line, "angle": edge_angle}


def _select_reference_edge(area: Sequence[Point], entrance_points: Sequence[Point]) -> Optional[Tuple[Point, Point]]:
    if len(area) < 2:
        return None

    entrance_angle = _entrance_angle(entrance_points, area)
    if entrance_angle is None:
        entrance_angle = 0.0

    if entrance_points and len(entrance_points) >= 2:
        entrance_start = entrance_points[0]
        entrance_end = entrance_points[1]
    else:
        centroid = _polygon_centroid(area)
        entrance_start = centroid
        entrance_end = (centroid[0] + math.cos(math.radians(entrance_angle)), centroid[1] + math.sin(math.radians(entrance_angle)))

    entrance_vec_x = float(entrance_end[0] - entrance_start[0])
    entrance_vec_y = float(entrance_end[1] - entrance_start[1])
    entrance_len = math.hypot(entrance_vec_x, entrance_vec_y)
    if entrance_len <= 0:
        return None

    best_edge = None
    best_score = None
    fallback_edge = None
    fallback_score = None

    for index in range(len(area)):
        p1 = area[index]
        p2 = area[(index + 1) % len(area)]
        edge_dx = float(p2[0] - p1[0])
        edge_dy = float(p2[1] - p1[1])
        edge_len = math.hypot(edge_dx, edge_dy)
        if edge_len <= 0:
            continue

        edge_angle = math.degrees(math.atan2(edge_dy, edge_dx)) % 180.0
        angle_diff = abs(edge_angle - entrance_angle)
        angle_diff = min(angle_diff, 180.0 - angle_diff)

        midpoint_x = (float(p1[0]) + float(p2[0])) / 2.0
        midpoint_y = (float(p1[1]) + float(p2[1])) / 2.0
        distance = abs(
            (midpoint_x - float(entrance_start[0])) * (-(entrance_vec_y / entrance_len))
            + (midpoint_y - float(entrance_start[1])) * (entrance_vec_x / entrance_len)
        )

        score = (-distance, angle_diff, -edge_len)
        if fallback_score is None or score < fallback_score:
            fallback_score = score
            fallback_edge = (p1, p2)

        if angle_diff <= 25.0 and (best_score is None or score < best_score):
            best_score = score
            best_edge = (p1, p2)

    return best_edge or fallback_edge


def _build_free_mask(
    shape: Tuple[int, int],
    areas: List[List[Point]],
    obstacles: List[List[Point]],
    shift_x: float,
    shift_y: float,
) -> np.ndarray:
    mask = np.zeros(shape, dtype=np.uint8)
    filled = False
    for area in areas:
        area_pts = _to_shifted_int_points(area, shift_x, shift_y)
        if len(area_pts) < 3:
            continue
        cv2.fillPoly(mask, [area_pts], 1)
        filled = True

    if not filled:
        return mask.astype(bool)

    for obstacle in obstacles:
        obstacle_pts = _to_shifted_int_points(obstacle, shift_x, shift_y)
        if len(obstacle_pts) >= 3:
            cv2.fillPoly(mask, [obstacle_pts], 0)

    return mask.astype(bool)


def _build_reachable_mask(free_mask: np.ndarray, entrance_points: Sequence[Point]) -> Optional[np.ndarray]:
    seeds = []
    if len(entrance_points) >= 2:
        segment_points = _sample_line_points(entrance_points[0], entrance_points[1], samples=9)
        for entrance in segment_points:
            seeds.extend(_find_free_seeds_on_segment(free_mask, entrance))
    else:
        for entrance in entrance_points:
            seeds.extend(_find_free_seeds_on_segment(free_mask, entrance))

    if not seeds:
        return None

    h, w = free_mask.shape[:2]
    visited = np.zeros((h, w), dtype=bool)
    queue = deque()
    for seed in seeds:
        if visited[seed[1], seed[0]]:
            continue
        visited[seed[1], seed[0]] = True
        queue.append(seed)

    while queue:
        x, y = queue.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if nx < 0 or ny < 0 or nx >= w or ny >= h:
                continue
            if visited[ny, nx] or not free_mask[ny, nx]:
                continue
            visited[ny, nx] = True
            queue.append((nx, ny))

    return visited


def _find_free_seeds_on_segment(free_mask: np.ndarray, entrance: Point, max_radius: int = 60) -> List[Tuple[int, int]]:
    h, w = free_mask.shape[:2]
    cx = int(round(entrance[0]))
    cy = int(round(entrance[1]))
    cx = min(max(cx, 0), w - 1)
    cy = min(max(cy, 0), h - 1)

    if free_mask[cy, cx]:
        return [(cx, cy)]

    for radius in range(1, max_radius + 1):
        for dx in range(-radius, radius + 1):
            for dy in (-radius, radius):
                nx, ny = cx + dx, cy + dy
                if 0 <= nx < w and 0 <= ny < h and free_mask[ny, nx]:
                    return [(nx, ny)]
        for dy in range(-radius + 1, radius):
            for dx in (-radius, radius):
                nx, ny = cx + dx, cy + dy
                if 0 <= nx < w and 0 <= ny < h and free_mask[ny, nx]:
                    return [(nx, ny)]

    return []


def _sample_line_points(start: Point, end: Point, samples: int = 9) -> List[Point]:
    if samples <= 1:
        return [start]

    points = []
    for index in range(samples):
        t = index / (samples - 1)
        points.append(
            (
                start[0] + (end[0] - start[0]) * t,
                start[1] + (end[1] - start[1]) * t,
            )
        )
    return points


def _rect_within_bounds(x: int, y: int, w: int, h: int, canvas_w: int, canvas_h: int) -> bool:
    return x >= 0 and y >= 0 and x + w <= canvas_w and y + h <= canvas_h


def _rect_is_free(free_mask: np.ndarray, x: int, y: int, w: int, h: int) -> bool:
    region = free_mask[y : y + h, x : x + w]
    return region.size > 0 and bool(np.all(region))


def _rect_has_access(
    free_mask: np.ndarray,
    reachable_mask: np.ndarray,
    x: int,
    y: int,
    w: int,
    h: int,
    edge_clearance_px: int,
) -> bool:
    candidate_points = [
        (x + w // 2, y - edge_clearance_px),
        (x + w // 2, y + h + edge_clearance_px - 1),
        (x - edge_clearance_px, y + h // 2),
        (x + w + edge_clearance_px - 1, y + h // 2),
    ]
    h_limit, w_limit = free_mask.shape[:2]
    for px, py in candidate_points:
        if 0 <= px < w_limit and 0 <= py < h_limit and free_mask[py, px] and reachable_mask[py, px]:
            return True
    return False


def _rect_access_point(
    free_mask: np.ndarray,
    reachable_mask: np.ndarray,
    x: int,
    y: int,
    w: int,
    h: int,
    edge_clearance_px: int,
) -> Optional[Point]:
    candidate_points = [
        (x + w // 2, y - edge_clearance_px),
        (x + w // 2, y + h + edge_clearance_px - 1),
        (x - edge_clearance_px, y + h // 2),
        (x + w + edge_clearance_px - 1, y + h // 2),
    ]
    h_limit, w_limit = free_mask.shape[:2]
    for px, py in candidate_points:
        if 0 <= px < w_limit and 0 <= py < h_limit and free_mask[py, px] and reachable_mask[py, px]:
            return float(px), float(py)
    return None


def get_rotated_rect_points(cx: float, cy: float, w: float, h: float, angle_deg: float) -> np.ndarray:
    angle_rad = math.radians(angle_deg)
    cos_a = math.cos(angle_rad)
    sin_a = math.sin(angle_rad)
    hw, hh = w / 2.0, h / 2.0
    corners = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
    rotated = []
    for x, y in corners:
        rx = x * cos_a - y * sin_a
        ry = x * sin_a + y * cos_a
        rotated.append([int(round(cx + rx)), int(round(cy + ry))])
    return np.array(rotated, dtype=np.int32)


def _polygon_area(mask: np.ndarray) -> int:
    return int(mask.sum())


def _build_padding_shift(points: Iterable[Point]) -> Tuple[float, float, int, int]:
    pts = list(points)
    xs = [point[0] for point in pts]
    ys = [point[1] for point in pts]
    min_x = math.floor(min(xs))
    min_y = math.floor(min(ys))
    max_x = math.ceil(max(xs))
    max_y = math.ceil(max(ys))
    pad = 40
    shift_x = -min_x + pad
    shift_y = -min_y + pad
    canvas_w = int(max_x - min_x + pad * 2 + 1)
    canvas_h = int(max_y - min_y + pad * 2 + 1)
    return shift_x, shift_y, canvas_w, canvas_h


def _points_bbox(points: Sequence[Point]) -> Optional[Tuple[int, int, int, int]]:
    if not points:
        return None
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return (
        int(math.floor(min(xs))),
        int(math.floor(min(ys))),
        int(math.ceil(max(xs))),
        int(math.ceil(max(ys))),
    )


def _shift_point(point: Point, shift_x: float, shift_y: float) -> Point:
    return point[0] + shift_x, point[1] + shift_y


def _to_shifted_int_points(points: Sequence[Point], shift_x: float, shift_y: float) -> np.ndarray:
    if not points:
        return np.empty((0, 1, 2), dtype=np.int32)
    arr = np.array([[point[0] + shift_x, point[1] + shift_y] for point in points], dtype=np.float32)
    return np.round(arr).astype(np.int32).reshape((-1, 1, 2))


def _rotate_point(point: Point, origin: Point, angle_deg: float) -> Point:
    angle_rad = math.radians(angle_deg)
    cos_a = math.cos(angle_rad)
    sin_a = math.sin(angle_rad)
    tx = point[0] - origin[0]
    ty = point[1] - origin[1]
    rx = tx * cos_a - ty * sin_a
    ry = tx * sin_a + ty * cos_a
    return rx + origin[0], ry + origin[1]


def _rotate_polygon(points: Sequence[Point], origin: Point, angle_deg: float) -> List[Point]:
    return [_rotate_point(point, origin, angle_deg) for point in points]


def _parse_point(value) -> Optional[Point]:
    if isinstance(value, dict):
        if "x" in value and "y" in value:
            return float(value["x"]), float(value["y"])
        if "cx" in value and "cy" in value:
            return float(value["cx"]), float(value["cy"])
    if isinstance(value, (list, tuple)) and len(value) >= 2:
        first, second = value[0], value[1]
        if isinstance(first, (list, tuple, dict)) or isinstance(second, (list, tuple, dict)):
            return None
        return float(first), float(second)
    return None


def _parse_areas(raw) -> List[List[Point]]:
    raw_areas = raw.get("areas") or raw.get("parking_areas")
    if raw_areas:
        parsed = []
        if isinstance(raw_areas, list):
            # Accept both:
            # - a single polygon: [[x, y], ...]
            # - multiple polygons: [[[x, y], ...], [[x, y], ...]]
            if raw_areas and _parse_point(raw_areas[0]) is not None:
                points = [point for point in (_parse_point(item) for item in raw_areas) if point is not None]
                if len(points) >= 3:
                    return [points]

            for area in raw_areas:
                if not isinstance(area, (list, tuple)):
                    continue
                points = [point for point in (_parse_point(item) for item in area) if point is not None]
                if len(points) >= 3:
                    parsed.append(points)
        if parsed:
            return parsed

    polygon = raw.get("polygon") or raw.get("parking_polygon") or raw.get("area") or raw.get("parking_area")
    if not polygon:
        return []
    points = [point for point in (_parse_point(item) for item in polygon) if point is not None]
    return [points] if len(points) >= 3 else []


def _default_weights_path() -> str:
    current_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(current_dir)
    return os.path.join(project_root, "video_test", "weights", "visDrone.pt")


def _parse_obstacles(raw) -> List[List[Point]]:
    obstacle_groups = raw.get("obstacles") or raw.get("obstacle_polygons") or raw.get("excluded_polygons") or []
    parsed = []
    if isinstance(obstacle_groups, list):
        # Mirror the area parser: support either one polygon or a list of polygons.
        if obstacle_groups and _parse_point(obstacle_groups[0]) is not None:
            points = [point for point in (_parse_point(item) for item in obstacle_groups) if point is not None]
            if len(points) >= 3:
                return [points]

        for group in obstacle_groups:
            if not isinstance(group, (list, tuple)):
                continue
            points = [point for point in (_parse_point(item) for item in group) if point is not None]
            if len(points) >= 3:
                parsed.append(points)
    return parsed


def _parse_entrances(raw) -> List[List[Point]]:
    raw_entrances = raw.get("entrances") or raw.get("area_entrances") or raw.get("entrance_segments")
    if not raw_entrances or not isinstance(raw_entrances, list):
        return []

    parsed = []
    for entrance in raw_entrances:
        points = _parse_entrance(entrance)
        if len(points) >= 2:
            parsed.append(points[:2])
        else:
            parsed.append([])
    return parsed


def _parse_entrance(raw_value) -> List[Point]:
    if raw_value is None:
        return []

    if isinstance(raw_value, dict):
        point = _parse_point(raw_value)
        return [point] if point is not None else []

    if isinstance(raw_value, (list, tuple)):
        if len(raw_value) >= 2 and _parse_point(raw_value[0]) is not None and _parse_point(raw_value[1]) is not None:
            points = [point for point in (_parse_point(item) for item in raw_value[:2]) if point is not None]
            return points if points else []
        point = _parse_point(raw_value)
        return [point] if point is not None else []

    return []


def _polygon_centroid(points: Sequence[Point]) -> Point:
    if not points:
        return (0.0, 0.0)

    if len(points) == 1:
        return float(points[0][0]), float(points[0][1])

    area = 0.0
    cx = 0.0
    cy = 0.0
    for index in range(len(points)):
        x1, y1 = points[index]
        x2, y2 = points[(index + 1) % len(points)]
        cross = x1 * y2 - x2 * y1
        area += cross
        cx += (x1 + x2) * cross
        cy += (y1 + y2) * cross

    area *= 0.5
    if abs(area) < 1e-9:
        xs = [point[0] for point in points]
        ys = [point[1] for point in points]
        return float(sum(xs) / len(xs)), float(sum(ys) / len(ys))

    factor = 1.0 / (6.0 * area)
    return float(cx * factor), float(cy * factor)


def _entrance_angle(entrance_points: Sequence[Point], area: Sequence[Point]) -> Optional[float]:
    if len(entrance_points) >= 2:
        start = entrance_points[0]
        end = entrance_points[1]
        angle = math.degrees(math.atan2(float(end[1] - start[1]), float(end[0] - start[0])))
        return float(angle % 180.0)

    if len(entrance_points) == 1:
        centroid = _polygon_centroid(area)
        point = entrance_points[0]
        angle = math.degrees(math.atan2(float(point[1] - centroid[1]), float(point[0] - centroid[0])))
        return float(angle % 180.0)

    return None
