FROM python:3.12-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY parking_yolo ./parking_yolo
COPY server0.py .

EXPOSE 8000
CMD ["uvicorn", "server0:app", "--host", "0.0.0.0", "--port", "8000"]
