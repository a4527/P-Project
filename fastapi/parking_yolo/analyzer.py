import threading
import time

import cv2
import torch
from ultralytics import YOLO

from .source_discovery import build_source_signature, discover_video_sources


class ParkingAnalyzer:
    vehicle_ids = [3, 4, 5, 9]

    def __init__(self, settings):
        self.settings = settings
        torch.set_num_threads(max(1, settings.torch_threads))
        try:
            torch.set_num_interop_threads(1)
        except RuntimeError:
            pass
        self.model = YOLO(settings.weights_path)
        self.status_cache = {"last_update": None}
        self.active_sources = []
        self.active_source_signature = None
        self.last_analysis = {}
        self.last_analysis_at = {}
        self.source_lock = threading.Lock()

    def get_status(self):
        with self.source_lock:
            return self.status_cache

    def refresh_sources(self, force=False):
        discovered = discover_video_sources(self.settings)
        signature = build_source_signature(self.settings, discovered)

        if not force and signature == self.active_source_signature:
            return

        with self.source_lock:
            self.active_sources = discovered
            self.active_source_signature = signature

            current_keys = {source["key"] for source in discovered}
            for key in list(self.last_analysis.keys()):
                if key not in current_keys:
                    del self.last_analysis[key]
                    self.last_analysis_at.pop(key, None)
            for key in current_keys:
                self.last_analysis.setdefault(key, set())
                self.last_analysis_at.setdefault(key, 0.0)

        print(f"loaded video sources: {', '.join(sorted(current_keys)) if current_keys else 'none'}")

    def run_forever(self):
        self.refresh_sources(force=True)
        caps = {}
        last_scan_time = 0.0
        frame_count = 0

        while True:
            now = time.monotonic()
            if now - last_scan_time >= self.settings.source_scan_interval:
                self.refresh_sources()
                with self.source_lock:
                    current_sources = list(self.active_sources)
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

            with self.source_lock:
                current_sources = list(self.active_sources)

            for source in current_sources:
                key = source["key"]
                cap = caps.get(key)
                if cap is None:
                    continue

                ret, frame = cap.read()
                if not ret:
                    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    continue

                should_analyze = (
                    frame_count % self.settings.frame_interval == 0
                    and now - self.last_analysis_at.get(key, 0.0) >= self.settings.analysis_interval
                )
                if should_analyze:
                    self._analyze_frame(source, frame)
                    self.last_analysis_at[key] = now

            self._publish_status()
            frame_count += 1
            time.sleep(self.settings.loop_interval)

    def _analyze_frame(self, source, frame):
        small = cv2.resize(frame, (self.settings.yolo_width, self.settings.yolo_height))
        results = self.model(small, classes=self.vehicle_ids, conf=0.35, verbose=False)[0]
        boxes = results.boxes.xyxy.cpu().numpy() if results.boxes is not None else []

        occupied_now = set()
        for bx1, by1, bx2, by2 in boxes:
            bcx, bcy = (bx1 + bx2) / 2, (by1 + by2) / 2
            for slot_id, data in source["map_data"].items():
                rx1, ry1, rx2, ry2 = data["rect"]
                if rx1 <= bcx <= rx2 and ry1 <= bcy <= ry2:
                    occupied_now.add(slot_id)

        with self.source_lock:
            self.last_analysis[source["key"]] = occupied_now

    def _publish_status(self):
        new_status = {"last_update": time.time()}
        with self.source_lock:
            status_sources = list(self.active_sources)
            analysis_snapshot = {key: set(value) for key, value in self.last_analysis.items()}

        for source in status_sources:
            key = source["key"]
            slots_info = []
            available_normal = 0
            available_disabled = 0
            occupied_set = analysis_snapshot.get(key, set())

            for slot_id, data in source["map_data"].items():
                is_occupied = slot_id in occupied_set
                slot_type = data["type"]
                if not is_occupied:
                    if slot_type == "disabled":
                        available_disabled += 1
                    else:
                        available_normal += 1

                slots_info.append({
                    "slot_id": slot_id,
                    "type": slot_type,
                    "status": "occupied" if is_occupied else "available",
                    "center": data["center"],
                })

            new_status[key] = {
                "summary": {
                    "total": len(source["map_data"]),
                    "available": available_normal + available_disabled,
                    "disabled_available": available_disabled,
                },
                "slots": slots_info,
            }

        with self.source_lock:
            self.status_cache = new_status
