"""FazeMES loopback-only SFace feature worker; no identities, images or tokens are issued here.
Adapted from yunshu-mes-all/face-auth. YuNet supplies landmarks for SFace alignment.
"""
from __future__ import annotations
import argparse
import base64
import binascii
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import threading

os.environ.setdefault("OPENCV_IO_MAX_IMAGE_PIXELS", "4194304")
import cv2
import numpy as np

MAX_BODY = 2_200_000
MODEL_HASHES = {
    "face_recognition_sface_2021dec.onnx": "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
    "face_detection_yunet_2023mar.onnx": "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
}

class FrameError(ValueError):
    pass

def decode_frame(value):
    if not isinstance(value, str) or not 64 <= len(value) <= 2_000_000:
        raise FrameError("Invalid frame length")
    if value.startswith("data:"):
        if not value.startswith(("data:image/jpeg;base64,", "data:image/png;base64,")):
            raise FrameError("Only JPEG or PNG frames are accepted")
        value = value.split(",", 1)[1]
    try:
        raw = base64.b64decode(value, validate=True)
        if not raw.startswith((b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n")):
            raise FrameError("Only JPEG or PNG frames are accepted")
        image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
    except (binascii.Error, ValueError, cv2.error) as exc:
        raise FrameError("Frame cannot be decoded") from exc
    if image is None or not 80 <= image.shape[0] <= 1920 or not 80 <= image.shape[1] <= 1920:
        raise FrameError("Frame dimensions must be 80–1920 pixels")
    return image

class FeatureEngine:
    def __init__(self, model_dir):
        model_dir = Path(model_dir)
        for name, digest in MODEL_HASHES.items():
            path = model_dir / name
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise RuntimeError("Face model missing or checksum mismatch: " + name)
        cv2.ocl.setUseOpenCL(False)
        cv2.setNumThreads(1)
        self.detector = cv2.FaceDetectorYN.create(
            str(model_dir / "face_detection_yunet_2023mar.onnx"), "", (320, 320),
            0.90, 0.3, 5000, cv2.dnn.DNN_BACKEND_OPENCV, cv2.dnn.DNN_TARGET_CPU)
        self.recognizer = cv2.FaceRecognizerSF.create(
            str(model_dir / "face_recognition_sface_2021dec.onnx"), "",
            cv2.dnn.DNN_BACKEND_OPENCV, cv2.dnn.DNN_TARGET_CPU)
        self.lock = threading.Lock()

    def extract(self, image):
        with self.lock:
            self.detector.setInputSize((image.shape[1], image.shape[0]))
            _, faces = self.detector.detect(image)
            if faces is None or len(faces) != 1:
                raise FrameError("Exactly one clear face is required")
            face = faces[0]
            if min(float(face[2]), float(face[3])) < 80:
                raise FrameError("Move closer to the camera")
            aligned = self.recognizer.alignCrop(image, face)
            vector = np.asarray(self.recognizer.feature(aligned), dtype=np.float32).reshape(-1)
        norm = float(np.linalg.norm(vector))
        if vector.size != 128 or not np.isfinite(vector).all() or norm <= 0:
            raise FrameError("Invalid face feature")
        return (vector / norm).tolist()

def handler_type(engine, token):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *_):
            pass  # Never log frames, feature vectors, identity or headers.
        def setup(self):
            super().setup()
            self.connection.settimeout(15)
        def respond(self, status, data):
            body = json.dumps(data, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        def authorized(self):
            return secrets.compare_digest(self.headers.get("X-Faze-Face-Token", ""), token)
        def do_GET(self):
            if not self.authorized():
                return self.respond(401, {"error": "Unauthorized"})
            if self.path != "/health":
                return self.respond(404, {"error": "Not found"})
            self.respond(200, {"ready": True, "model": "SFace+YuNet", "dimensions": 128})
        def do_POST(self):
            if not self.authorized():
                return self.respond(401, {"error": "Unauthorized"})
            if self.path != "/extract":
                return self.respond(404, {"error": "Not found"})
            try:
                if self.headers.get("Transfer-Encoding"):
                    raise FrameError("Chunked requests are not accepted")
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BODY:
                    return self.respond(413, {"error": "Frame too large"})
                if self.headers.get_content_type() != "application/json":
                    return self.respond(415, {"error": "JSON required"})
                request = json.loads(self.rfile.read(length))
                if not isinstance(request, dict) or set(request) != {"imageBase64"}:
                    raise FrameError("Expected imageBase64 only")
                feature = engine.extract(decode_frame(request["imageBase64"]))
                self.respond(200, {"feature": feature})
            except (FrameError, ValueError, UnicodeError, cv2.error):
                self.respond(422, {"error": "Exactly one clear face in a valid frame is required"})
            except Exception:
                self.respond(503, {"error": "Face service unavailable"})
    return Handler

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--port", type=int, default=8091)
    args = parser.parse_args()
    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    if len(token) < 40:
        raise RuntimeError("Missing local service token")
    engine = FeatureEngine(args.model_dir)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_type(engine, token))
    server.daemon_threads = True
    print("FazeMES face feature service ready on loopback.", flush=True)
    server.serve_forever()

if __name__ == "__main__":
    main()
