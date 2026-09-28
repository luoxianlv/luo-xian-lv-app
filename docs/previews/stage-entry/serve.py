"""Local, allowlisted preview server. Run from anywhere: python serve.py --port 18459."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import mimetypes
import re
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
ASSETS = REPO / "app/src/main/assets"
PREVIEW = HERE / "assets"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        route = urlsplit(self.path).path
        paths = {
            "/": HERE / "index.html", "/index.html": HERE / "index.html",
            "/style.css": HERE / "style.css", "/app.js": HERE / "app.js",
            "/assets/hero.png": ASSETS / "hero_home.png",
            "/assets/day.mp4": PREVIEW / "day-preview.mp4",
            "/assets/poster.jpg": PREVIEW / "poster.jpg",
            "/assets/sunset.jpg": ASSETS / "practice-sunset.jpg",
            "/audio/index.tsv": ASSETS / "harmonica/index.tsv",
        }
        path = paths.get(route)
        match = re.fullmatch(r"/audio/(\d+)\.pcm", route)
        if match and 48 <= int(match[1]) <= 85:
            path = ASSETS / "harmonica" / f"{match[1]}.pcm"
        if path is None or not path.is_file():
            self.send_error(404)
            return
        size = path.stat().st_size
        start, end, status = 0, size - 1, 200
        if self.headers.get("Range"):
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers["Range"])
            if not match:
                self.send_error(416)
                return
            start = int(match[1]); end = min(int(match[2]), end) if match[2] else end
            if start > end:
                self.send_error(416)
                return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        try:
            with path.open("rb") as stream:
                stream.seek(start)
                remaining = end - start + 1
                while remaining:
                    chunk = stream.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18459)
    args = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
