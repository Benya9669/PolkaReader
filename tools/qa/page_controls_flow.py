"""Exercise cropping and one-handed input against the reader_ux_fixture demo books."""
import argparse
import io
import os
import re
import subprocess
import time

import httpx
from PIL import Image

from phone_flow import Device
from reader_ux_fixture import EPUB_TITLE, PDF_TITLE


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=18764)
    args = parser.parse_args()
    d = Device(args.serial)
    d.restart()
    d.rotate("1")

    def exact(label: str, tap: bool = True):
        d.find(label)
        nodes = [n for n in d.nodes() if label in (n.get("text"), n.get("content-desc"))]
        assert nodes, f"Отсутствует кнопка {label!r}"
        if tap:
            d.tap(nodes[-1])
        return nodes[-1]

    def choose(title: str) -> None:
        exact("Библиотека")
        exact(title)
        exact("Читать")
        exact("Перелистывание", False)

    def bounds(node) -> tuple[int, ...]:
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def move_slider(label: str, portion: float) -> None:
        x1, y1, x2, y2 = bounds(exact(label, False))
        x = round(x1 + portion * (x2 - x1))
        d.adb("shell", "input", "tap", str(x), str((y1 + y2) // 2))

    def save_position(client: httpx.Client, file_id: int):
        return client.get(f"reading/progress/{file_id}").raise_for_status().json()

    def await_locator(client: httpx.Client, ident: int, locator: str | None, before=None):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            current = save_position(client, ident)
            if current.get("version", 0) > (before or 0) and (current.get("locator") == locator if locator else current.get("locator")):
                return current
            time.sleep(.3)
        raise AssertionError(f"Позиция файла {ident} не синхронизировалась")

    with httpx.Client(base_url=f"http://127.0.0.1:{args.port}/api/v1/", timeout=30, trust_env=False) as api:
        api.post("auth/login", json={"username": "readerone", "password": os.environ["POLKA_QA_PASSWORD"]}).raise_for_status()
        try:
            def file_id(title: str) -> int:
                works = api.get("books", params={"q": title}).raise_for_status().json()["items"]
                return next(w for w in works if w["title"] == title)["editions"][0]["files"][0]["id"]

            pdf, epub = file_id(PDF_TITLE), file_id(EPUB_TITLE)
            choose(PDF_TITLE)
            if not any("Стр. 1 из 5" in n.get("text", "") for n in d.nodes()):
                exact("Перейти к странице")
                d.field(0, "1")
                d.adb("shell", "input", "keyevent", "4")
                exact("Перейти")
                await_locator(api, pdf, "1")
            d.find("Стр. 1 из 5")
            before = save_position(api, pdf).get("version")
            exact("Поля PDF")
            exact("По этой странице")
            exact("Слева")
            move_slider("Обрезка поля: Слева", .42)
            left = next(n.get("text") for n in d.nodes() if n.get("text", "").startswith("Слева · "))
            assert left != "Слева · 0%"
            exact("Сохранить")
            assert save_position(api, pdf).get("version") == before
            exact("Поля PDF")
            exact(left, False)
            exact("Сбросить")
            exact("Отмена")
            exact("Поля PDF")
            exact(left, False)
            exact("Отмена")
            print("Обрезка PDF обновилась без записи позиции, отмена восстановила сохранённые поля.")
            exact("Перелистывание")
            exact("Левый")
            move_slider("Ширина зоны касания", .97)
            # Compose exposes the label inside the switch's checkable/toggleable parent.
            volume = next(n for n in d.nodes() if n.get("checkable") == "true" and any(
                child.get("content-desc") == "Листать кнопками громкости" for child in n.iter()))
            if volume.get("checked") != "true":
                d.tap(volume)
            exact("Готово")
            with Image.open(io.BytesIO(subprocess.check_output(d.target + ["exec-out", "screencap", "-p"]))) as image:
                width, height = image.size
            # 30% is the centre with default settings. After widening it belongs to the left forward zone.
            d.adb("shell", "input", "tap", str(round(width * .30)), str(height // 2))
            d.find("Стр. 2 из 5")
            v2 = await_locator(api, pdf, "2", before)
            d.adb("shell", "input", "keyevent", "25")
            d.find("Стр. 3 из 5")
            v3 = await_locator(api, pdf, "3", v2["version"])
            d.adb("shell", "input", "keyevent", "24")
            d.find("Стр. 2 из 5")
            await_locator(api, pdf, "2", v3["version"])
            print("Широкая левая зона и обе аппаратные клавиши перелистнули PDF.")
            d.restart()
            exact("Продолжить чтение")
            d.find("Стр. 2 из 5")
            exact("Поля PDF")
            exact(left, False)
            exact("Отмена")
            print("После перезапуска восстановились точная страница PDF и индивидуальная обрезка.")
            exact("Выйти из книги")
            choose(EPUB_TITLE)
            old = save_position(api, epub).get("version")
            d.adb("shell", "input", "keyevent", "25")
            first = await_locator(api, epub, None, old)
            d.adb("shell", "input", "tap", str(round(width * .30)), str(height // 2))
            second = await_locator(api, epub, None, first["version"])
            assert second["locator"] != first["locator"]
            d.adb("shell", "input", "keyevent", "24")
            restored = await_locator(api, epub, None, second["version"])
            assert restored["locator"] == first["locator"]
            print("Автоподбор, ручная обрезка, отмена, холодный старт, края и кнопки громкости EPUB/PDF прошли.")
        finally:
            api.post("auth/logout").raise_for_status()


if __name__ == "__main__":
    main()
