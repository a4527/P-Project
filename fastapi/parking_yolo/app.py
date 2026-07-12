import threading

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .analyzer import ParkingAnalyzer
from .config import load_settings


def create_app(start_worker=True, analyzer=None):
    if analyzer is None:
        settings = load_settings()
        analyzer = ParkingAnalyzer(settings)

    app = FastAPI()
    app.state.analyzer = analyzer

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.get("/status")
    def get_status():
        return analyzer.get_status()

    if start_worker:
        threading.Thread(target=analyzer.run_forever, daemon=True).start()

    return app
