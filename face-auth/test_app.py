"""Synthetic-frame regression tests; never opens a camera or reads person photographs."""
from __future__ import annotations

import base64
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
from http.server import ThreadingHTTPServer

import cv2
import numpy as np

SPEC = importlib.util.spec_from_file_location("fazemes_face_worker", Path(__file__).with_name("app.py"))
worker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker)
MODEL_DIR = Path(__file__).resolve().parents[1] / ".runtime" / "face-auth" / "models"


def blank_frame(size=320):
    frame = np.full((size, size, 3), 255, dtype=np.uint8)
    success, encoded = cv2.imencode(".png", frame)
    assert success
    return base64.b64encode(encoded).decode("ascii")


class DecodeFrameTest(unittest.TestCase):
    def test_png_is_decoded_in_memory(self):
        encoded = blank_frame()
        frame = worker.decode_frame("data:image/png;base64," + encoded)
        self.assertEqual((320, 320, 3), frame.shape)

    def test_invalid_type_length_magic_format_and_dimensions_are_rejected(self):
        values = [None, {}, "x", "x" * 2_000_001, "!" * 100,
                  base64.b64encode(b"GIF89a" + b"0" * 100).decode(),
                  "data:image/svg+xml;base64," + "A" * 100,
                  blank_frame(79), blank_frame(1921)]
        for value in values:
            with self.subTest(kind=type(value).__name__, length=len(value) if isinstance(value, str) else 0):
                with self.assertRaises(worker.FrameError):
                    worker.decode_frame(value)


class DetectionGuardsTest(unittest.TestCase):
    def engine(self, faces, feature=None):
        engine = object.__new__(worker.FeatureEngine)
        engine.lock = threading.Lock()
        engine.detector = Mock()
        engine.detector.detect.return_value = (None, faces)
        engine.recognizer = Mock()
        engine.recognizer.feature.return_value = np.asarray(feature if feature is not None else [1.0] + [0.0] * 127, dtype=np.float32)
        return engine

    def test_no_face_and_multiple_detections_reject_before_feature_extraction(self):
        # Synthetic detector outputs exercise the count gate without introducing human images.
        for faces in (None, np.zeros((0, 15)), np.zeros((2, 15))):
            engine = self.engine(faces)
            with self.assertRaises(worker.FrameError):
                engine.extract(np.zeros((320, 320, 3), dtype=np.uint8))
            engine.recognizer.feature.assert_not_called()

    def test_small_face_and_nonfinite_or_bad_dimension_features_are_rejected(self):
        small = np.zeros((1, 15), dtype=np.float32)
        small[0, 2:4] = 79
        with self.assertRaises(worker.FrameError):
            self.engine(small).extract(np.zeros((320, 320, 3), dtype=np.uint8))
        face = np.zeros((1, 15), dtype=np.float32)
        face[0, 2:4] = 100
        for feature in ([float("nan")] + [0.0] * 127, [float("inf")] + [0.0] * 127, [0.0] * 128, [1.0] * 127):
            with self.assertRaises(worker.FrameError):
                self.engine(face, feature).extract(np.zeros((320, 320, 3), dtype=np.uint8))


@unittest.skipUnless(all((MODEL_DIR / name).is_file() for name in worker.MODEL_HASHES), "verified model files unavailable")
class RealBlankDetectionTest(unittest.TestCase):
    def test_verified_yunet_rejects_blank_frame_without_writing_images(self):
        engine = worker.FeatureEngine(MODEL_DIR)
        with tempfile.TemporaryDirectory() as temporary:
            before = list(Path(temporary).iterdir())
            # Images, features and headers are never persisted; model files are read before this guard.
            with patch.object(Path, "write_bytes", side_effect=AssertionError("image persistence")), \
                 patch.object(Path, "write_text", side_effect=AssertionError("image persistence")):
                with self.assertRaises(worker.FrameError):
                    engine.extract(worker.decode_frame(blank_frame()))
            self.assertEqual(before, list(Path(temporary).iterdir()))


class WorkerHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = Mock()
        cls.engine.extract.return_value = [1.0] + [0.0] * 127
        cls.token = "synthetic-worker-test-token-" + "x" * 40
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), worker.handler_type(cls.engine, cls.token))
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), json.loads(response.read())
        finally:
            connection.close()

    def authorized_headers(self):
        return {"X-Faze-Face-Token": self.token, "Content-Type": "application/json"}

    def test_health_and_extract_require_independent_worker_token(self):
        for method, path in (("GET", "/health"), ("POST", "/extract")):
            status, _, response = self.request(method, path, "{}", {"X-Faze-Face-Token": "wrong-token"})
            self.assertEqual(401, status)
            self.assertEqual({"error": "Unauthorized"}, response)

    def test_health_is_private_no_store_and_contains_no_identity(self):
        status, headers, response = self.request("GET", "/health", headers=self.authorized_headers())
        self.assertEqual(200, status)
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual({"ready": True, "model": "SFace+YuNet", "dimensions": 128}, response)

    def test_json_shape_content_type_size_and_unknown_routes_are_enforced(self):
        cases = [
            ("/extract", json.dumps({"imageBase64": blank_frame(), "userId": 42}), self.authorized_headers(), 422),
            ("/extract", json.dumps([]), self.authorized_headers(), 422),
            ("/extract", "{}", {"X-Faze-Face-Token": self.token, "Content-Type": "text/plain"}, 415),
            ("/extract", "", self.authorized_headers(), 413),
            ("/unknown", "{}", self.authorized_headers(), 404),
        ]
        for path, body, headers, expected in cases:
            with self.subTest(path=path, expected=expected):
                status, _, _ = self.request("POST", path, body, headers)
                self.assertEqual(expected, status)

    def test_valid_synthetic_frame_returns_only_feature_and_never_logs_payload(self):
        with patch.object(worker.BaseHTTPRequestHandler, "log_message", side_effect=AssertionError("sensitive request logging")):
            status, _, response = self.request("POST", "/extract", json.dumps({"imageBase64": blank_frame()}), self.authorized_headers())
        self.assertEqual(200, status)
        self.assertEqual({"feature"}, set(response))
        self.assertEqual(128, len(response["feature"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
