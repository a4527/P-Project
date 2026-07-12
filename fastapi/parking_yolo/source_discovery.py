import json
import os
import urllib.request
from urllib.parse import urljoin


def discover_video_sources(settings):
    sources = []
    try:
        with urllib.request.urlopen(settings.analysis_manifest_url, timeout=5) as response:
            manifest = json.loads(response.read().decode("utf-8"))
    except Exception as e:
        print(f"warning: failed to load analysis manifest: {e}")
        return sources

    os.makedirs(settings.cache_dir, exist_ok=True)
    for item in manifest.get("sources", []):
        key = item.get("partitionKey")
        video_url = item.get("videoUrl")
        slot_layout_json = item.get("slotLayoutJson")
        if not key or not video_url or not slot_layout_json:
            continue

        map_data = parse_map_data(slot_layout_json, f"manifest:{key}")
        if not map_data:
            print(f"warning: skipping manifest source without slot map: {key}")
            continue

        video_path = os.path.join(settings.cache_dir, f"{key}_video.mp4")
        download_url = video_url if video_url.startswith("http") else urljoin(settings.spring_base_url, video_url)
        if not download_file(download_url, video_path):
            print(f"warning: skipping manifest source without video: {key}")
            continue

        sources.append({
            "key": key,
            "video_path": video_path,
            "map_data": map_data,
        })

    return sources


def build_source_signature(settings, sources):
    signature = []
    for source in sources:
        video_mtime = os.path.getmtime(source["video_path"]) if os.path.exists(source["video_path"]) else None
        slot_mtime = hash(json.dumps(source["map_data"], sort_keys=True))
        signature.append((source["key"], video_mtime, slot_mtime))
    return tuple(signature)


def parse_map_data(raw_json, source_name):
    try:
        raw_data = json.loads(raw_json)
        parsed_data = {}
        for item in raw_data:
            slot_id = int(item["slot"])
            cx, cy = item["center"]
            w, h = item["w"], item["h"]
            parsed_data[slot_id] = {
                "rect": (int(cx - w / 2), int(cy - h / 2), int(cx + w / 2), int(cy + h / 2)),
                "center": [cx, cy],
                "type": item.get("type", "normal"),
            }
        print(f"loaded slot map: {source_name} ({len(parsed_data)} slots)")
        return parsed_data
    except Exception as e:
        print(f"failed to load slot map {source_name}: {e}")
        return {}


def download_file(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return True
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read()
        with open(path, "wb") as f:
            f.write(data)
        return True
    except Exception as e:
        print(f"failed to download video {url}: {e}")
        return False
