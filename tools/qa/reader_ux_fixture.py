"""Add two synthetic, visually distinct publications to the local serve_fixture.py library."""
import argparse
import io
import os
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

import httpx
from PIL import Image, ImageDraw, ImageFont


EPUB_TITLE = "Маяк на мысе"
PDF_TITLE = "Цветной атлас мыса"
SUMMARY = "Смотритель маяка находит карту побережья и возвращает на неё имена забытых бухт."
CHAPTERS = ("На берегу", "Карта побережья", "Возвращение")


def cover() -> bytes:
    image = Image.new("RGB", (320, 464), "#36565b")
    draw = ImageDraw.Draw(image)
    draw.ellipse((196, 93, 271, 168), fill="#efc381")
    draw.polygon([(0, 306), (80, 240), (170, 270), (231, 207), (320, 230), (320, 464), (0, 464)], fill="#538181")
    draw.polygon([(0, 326), (96, 290), (209, 306), (320, 255), (320, 464), (0, 464)], fill="#203e44")
    draw.polygon([(130, 299), (142, 184), (157, 184), (169, 299)], fill="#ffebbc")
    draw.rectangle((139, 172, 160, 187), fill="#e5ab5c")
    draw.polygon([(134, 171), (149, 158), (165, 171)], fill="#203e44")
    try:
        font = ImageFont.truetype("DejaVuSerif.ttf", 31)
    except OSError:
        font = ImageFont.load_default()
    draw.text((22, 25), "NORTH CAPE", font=font, fill="#ffebbc")
    buf = io.BytesIO()
    image.save(buf, "JPEG")
    return buf.getvalue()


def epub() -> bytes:
    buf = io.BytesIO()
    with ZipFile(buf, "w", ZIP_DEFLATED) as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
        archive.writestr("META-INF/container.xml", '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        items = "".join(f'<item id="ch{i}" href="ch{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(3))
        spine = "".join(f'<itemref idref="ch{i}"/>' for i in range(3))
        archive.writestr("OPS/book.opf", f'''<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:polka:qa:cape</dc:identifier>
          <dc:title>{EPUB_TITLE}</dc:title><dc:creator>Вера Ланская</dc:creator><dc:language>ru</dc:language>
          <dc:description>{SUMMARY}</dc:description><dc:publisher>Мастерская маяка</dc:publisher>
          <meta name="cover" content="cover"/><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
          <manifest><item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>{items}</manifest><spine>{spine}</spine></package>''')
        archive.writestr("OPS/cover.jpg", cover())
        header = '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
        entries = "".join(f'<li><a href="ch{i}.xhtml">{title}</a></li>' for i, title in enumerate(CHAPTERS))
        archive.writestr("OPS/nav.xhtml", f'{header}<head><title>Оглавление</title></head><body><nav epub:type="toc"><ol>{entries}</ol></nav></body></html>')
        for i, title in enumerate(CHAPTERS):
            paragraphs = "".join(
                f'<p id="p{n}">Запись {n}. По узкой тропинке смотритель вышел к заливу. За скалой раздавался шум моря. '
                'Когда вдали появился корабль, он положил карту на парапет и зажёг фонарь.</p>'
                for n in range(1, 61))
            archive.writestr(f"OPS/ch{i}.xhtml", f'''{header}<head><title>{title}</title></head><body><h1>{title}</h1>
              <p>На краю мыса стоял маяк. <a epub:type="noteref" href="ch2.xhtml#note">Примечание</a>.</p>
              <p><a href="ch1.xhtml#p4">Посмотреть карту</a></p>{paragraphs}
              <aside epub:type="footnote" id="note"><p>Створ — линия двух береговых знаков.</p></aside></body></html>''')
    return buf.getvalue()


def atlas() -> bytes:
    from pypdf import PdfWriter
    from pypdf.generic import DecodedStreamObject, NameObject

    writer = PdfWriter()
    for i in range(5):
        page = writer.add_blank_page(width=360, height=480)
        stream = DecodedStreamObject()
        # Each page has a different band and number of black marks: a spread is easy to distinguish.
        marks = " ".join(f"{38 + n * 49} 198 28 90 re f" for n in range(i + 1))
        stream.set_data(f"0.{i + 1} 0.42 0.52 rg 0 320 360 120 re f 0.12 0.18 0.17 rg {marks}".encode("ascii"))
        page[NameObject("/Contents")] = writer._add_object(stream)
    writer.add_metadata({"/Title": PDF_TITLE, "/Author": "Вера Ланская"})
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18764)
    args = parser.parse_args()
    with httpx.Client(base_url=f"http://127.0.0.1:{args.port}/api/v1/", trust_env=False) as api:
        api.post("auth/login", json={"username": "qaadmin", "password": os.environ["POLKA_QA_PASSWORD"]}).raise_for_status()
        try:
            library = next(l for l in api.get("libraries").raise_for_status().json() if l["name"] == "Проверочная библиотека")
            for title, filename, make in [(EPUB_TITLE, "cape.epub", epub), (PDF_TITLE, "cape-atlas.pdf", atlas)]:
                def work():
                    result = api.get("books", params={"q": title}).raise_for_status().json()
                    return next((b for b in result["items"] if b["title"] == title), None)
                if not work():
                    api.post(f"libraries/{library['id']}/upload", files={"file": (filename, make())}).raise_for_status()
                edition = work()["editions"][0]
                if title == EPUB_TITLE:
                    assert edition["summary"] == SUMMARY and edition["cover"], "Expected OPF metadata"
                print(f"{title}: file_id={edition['files'][0]['id']}")
        finally:
            api.post("auth/logout").raise_for_status()


if __name__ == "__main__":
    main()
