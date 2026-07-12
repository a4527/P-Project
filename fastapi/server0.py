import threading

from parking_yolo.app import create_app
from parking_yolo.analyzer import ParkingAnalyzer
from parking_yolo.config import load_settings


if __name__ == "__main__":
    import uvicorn

    settings = load_settings()
    analyzer = ParkingAnalyzer(settings)
    app = create_app(start_worker=False, analyzer=analyzer)

    threading.Thread(
        target=lambda: uvicorn.run(app, host="0.0.0.0", port=8000),
        daemon=True,
    ).start()
    analyzer.run_forever()
else:
    app = create_app(start_worker=True)
