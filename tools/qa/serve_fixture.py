"""Serve an isolated upstream Polka release on loopback with synthetic books."""
import argparse
import io
import os
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    parser.add_argument("state", type=Path)
    parser.add_argument("--port", type=int, default=18763)
    args = parser.parse_args()
    upstream, state = args.upstream.resolve(), args.state.resolve()
    state.mkdir(parents=True, exist_ok=True)
    password = os.environ["POLKA_QA_PASSWORD"]
    os.environ.update(
        APP_URL=f"http://127.0.0.1:{args.port}",
        DATABASE_URL=f"sqlite:///{(state / 'qa.sqlite').as_posix()}",
        LIBRARY_PATH=str(state / "books"), IMPORT_PATH=str(state / "inbox"),
        REDIS_URL="", WEB_ROOT=str(upstream / "frontend" / "dist"),
    )
    sys.path.insert(0, str(upstream / "backend"))
    from fastapi.testclient import TestClient
    from pypdf import PdfWriter
    import uvicorn
    from app.db import Base, engine
    from app.main import app
    from tests.fixtures import epub_bytes

    Base.metadata.create_all(engine)
    with TestClient(app) as client:
        if client.get("/api/v1/auth/status").json()["setup"]:
            client.post("/api/v1/auth/setup", json=dict(username="qaadmin", password=password)).raise_for_status()
            lib = client.post("/api/v1/libraries", json=dict(name="Проверочная библиотека", mode="managed", shared=True))
            lib.raise_for_status()
            endpoint = f"/api/v1/libraries/{lib.json()['id']}/upload"
            for username in ("readerone", "readertwo"):
                client.post("/api/v1/auth/users", json=dict(username=username, password=password, read_only=True)).raise_for_status()
            client.post(endpoint, files={"file": ("severnaya.epub", epub_bytes())}).raise_for_status()
            pdf = PdfWriter()
            for _ in range(24):
                pdf.add_blank_page(width=400, height=600)
            pdf.add_metadata({"/Title": "Атлас для проверки", "/Author": "Полка"})
            buf = io.BytesIO()
            pdf.write(buf)
            client.post(endpoint, files={"file": ("atlas.pdf", buf.getvalue())}).raise_for_status()
    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
