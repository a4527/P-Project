import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    base_dir: str
    weights_path: str
    yolo_width: int
    yolo_height: int
    frame_interval: int
    analysis_interval: float
    loop_interval: float
    torch_threads: int
    source_scan_interval: float
    analysis_manifest_url: str
    spring_base_url: str
    cache_dir: str


def load_settings() -> Settings:
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    weights_path = os.environ.get(
        "SMARTPARKING_YOLO_WEIGHTS",
        os.path.join(base_dir, "weights", "visDrone.pt"),
    )

    return Settings(
        base_dir=base_dir,
        weights_path=weights_path,
        yolo_width=int(os.environ.get("SMARTPARKING_YOLO_WIDTH", "854")),
        yolo_height=int(os.environ.get("SMARTPARKING_YOLO_HEIGHT", "480")),
        frame_interval=int(os.environ.get("SMARTPARKING_FRAME_INTERVAL", "30")),
        analysis_interval=float(os.environ.get("SMARTPARKING_ANALYSIS_INTERVAL", "5.0")),
        loop_interval=float(os.environ.get("SMARTPARKING_LOOP_INTERVAL", "0.01")),
        torch_threads=int(os.environ.get("SMARTPARKING_TORCH_THREADS", "2")),
        source_scan_interval=float(os.environ.get("SMARTPARKING_SOURCE_SCAN_INTERVAL", "5.0")),
        analysis_manifest_url=os.environ.get(
            "SMARTPARKING_ANALYSIS_MANIFEST_URL",
            "http://localhost:8080/api/internal/analysis/sources",
        ),
        spring_base_url=os.environ.get("SMARTPARKING_SPRING_BASE_URL", "http://localhost:8080"),
        cache_dir=os.environ.get("SMARTPARKING_ANALYSIS_CACHE_DIR", os.path.join(base_dir, ".cache")),
    )
