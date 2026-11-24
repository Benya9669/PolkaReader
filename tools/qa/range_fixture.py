"""Populate an isolated serve_fixture.py with large EPUB and PDF files for LiveRangeTest."""
import argparse
from io import BytesIO
import os
from zipfile import ZipFile, ZIP_STORED

import httpx
from pypdf import PdfReader, PdfWriter
from reader_ux_fixture import EPUB_TITLE, epub, atlas


def large_epub() -> bytes:
    output = BytesIO()
    with ZipFile(BytesIO(epub())) as original, ZipFile(output, "w") as result:
        for entry in original.infolist():
            data = original.read(entry)
            if entry.filename.endswith(".opf"):
                data = data.replace(EPUB_TITLE.encode(), "Проверка диапазонов EPUB".encode())
            result.writestr(entry, data)
        result.writestr("OPS/extra.bin", bytes(4 * 1024 * 1024), compress_type=ZIP_STORED)
    return output.getvalue()


def large_pdf() -> bytes:
    writer = PdfWriter(clone_from=PdfReader(BytesIO(atlas())))
    writer.add_metadata({"/Title": "Проверка диапазонов PDF"})
    writer.add_attachment("extra.bin", bytes(4 * 1024 * 1024))
    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18763)
    args = parser.parse_args()
    with httpx.Client(base_url=f"http://127.0.0.1:{args.port}/api/v1/", trust_env=False) as api:
        api.post("auth/login", json={"username": "qaadmin", "password": os.environ["POLKA_QA_PASSWORD"]}).raise_for_status()
        try:
            library = next(l for l in api.get("libraries").raise_for_status().json() if l["name"] == "Проверочная библиотека")
            for extension, generate in (("epub", large_epub), ("pdf", large_pdf)):
                title = f"Проверка диапазонов {extension.upper()}"
                if not api.get("books", params={"q": title}).raise_for_status().json()["items"]:
                    api.post(f"libraries/{library['id']}/upload", files={"file": (f"range.{extension}", generate())}).raise_for_status()
        finally:
            api.post("auth/logout").raise_for_status()


if __name__ == "__main__":
    main()
