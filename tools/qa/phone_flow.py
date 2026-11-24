"""Exercise the installed APK on the dedicated emulator against serve_fixture.py."""
import argparse
import io
import os
import re
import subprocess
import time
from xml.etree import ElementTree as ET


class Device:
    def __init__(self, serial: str) -> None:
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Use a dedicated emulator")
        self.target = ["adb", "-s", serial]

    def adb(self, *args: str) -> str:
        return subprocess.check_output(self.target + list(args)).decode("utf-8", "replace").strip()

    def nodes(self) -> list:
        raw = self.adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = raw.find("<hierarchy"), raw.rfind("</hierarchy>")
        return [] if min(start, end) < 0 else list(ET.fromstring(raw[start:end + 12]).iter("node"))

    def labels(self, nodes) -> list[str]:
        private = {child for node in nodes if node.get("password") == "true"
                   or node.get("class") == "android.widget.EditText" for child in node.iter()}
        if any(n.get("password") == "true" for n in nodes):
            return ["Форма входа"]
        secret = os.environ.get("POLKA_QA_PASSWORD")
        return [v for n in nodes if n not in private
                for v in (n.get("text", ""), n.get("content-desc", "")) if v and not (secret and secret in v)]

    def find(self, label: str, tap: bool = False, timeout: float = 30) -> None:
        deadline = time.monotonic() + timeout
        nodes = []
        while time.monotonic() < deadline:
            nodes = self.nodes()
            for node in nodes:
                if label not in " ".join([node.get("text", ""), node.get("content-desc", "")]):
                    continue
                if tap:
                    self.tap(node)
                return
            time.sleep(0.25)
        raise AssertionError(f"Не найден элемент «{label}»")

    def tap(self, node) -> None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def visible_epub_text(self, passage: str) -> None:
        """An accessibility node can exist even when a zero-height WebView clips all its glyphs."""
        from PIL import Image
        self.find(passage)
        node = next(n for n in self.nodes() if passage in n.get("text", ""))
        bounds = tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1], "Пустая область абзаца"
        capture = subprocess.check_output(self.target + ["exec-out", "screencap", "-p"])
        with Image.open(io.BytesIO(capture)) as image:
            histogram = image.crop(bounds).convert("L").histogram()
        paper = max(range(256), key=histogram.__getitem__)
        ink = sum(count for shade, count in enumerate(histogram) if abs(shade - paper) > 90)
        assert ink >= 30, "WebView содержит абзац, но его текст не нарисован на экране"

    def field(self, index: int, text: str) -> None:
        if not re.fullmatch(r"[\w.:/-]+", text):
            raise ValueError("Test fixture accepts alphanumeric input")
        # On a landscape tablet the IME changes the viewport after every focus change.
        if re.search(r"mInputShown=true\b", self.adb("shell", "dumpsys", "input_method")):
            self.adb("shell", "input", "keyevent", "4")
            time.sleep(0.5)
        fields = [n for n in self.nodes() if n.get("class") == "android.widget.EditText"]
        self.tap(fields[index])
        is_secret = text == os.environ.get("POLKA_QA_PASSWORD")
        ready = False
        for _ in range(4):
            current = [n for n in self.nodes() if n.get("class") == "android.widget.EditText"]
            if is_secret:
                ready = any(n.get("focused") == "true" and n.get("password") == "true" for n in current)
            else:
                ready = index < len(current) and current[index].get("focused") == "true"
            if ready:
                break
        if not ready:
            raise AssertionError("Не удалось подтвердить фокус выбранного поля")
        self.adb("shell", "input", "keycombination", "113", "29")
        self.adb("shell", "input", "keyevent", "67")
        # Send synthetic login inputs over stdin, away from host command-line history.
        subprocess.run(self.target + ["shell", "-T"], input=f"input text '{text}'\n".encode(), check=True)

    def start(self) -> None:
        self.adb("shell", "am", "start", "-n", "ru.polka.reader/.MainActivity")

    def restart(self) -> None:
        self.adb("shell", "am", "force-stop", "ru.polka.reader")
        self.start()

    def airplane(self, enabled: bool) -> None:
        # The fixture uses adb reverse, which can bypass the emulator's radios.
        port = "tcp:18763"
        if enabled and port in self.adb("reverse", "--list"):
            self.adb("reverse", "--remove", port)
        if not enabled:
            self.adb("reverse", port, port)
        self.adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if enabled else "disable")
        time.sleep(2)

    def rotate(self, direction: str) -> None:
        self.adb("shell", "wm", "user-rotation", "lock", direction)
        time.sleep(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["login", "epub", "offline", "pdf", "inspect"])
    parser.add_argument("--serial", default="emulator-5582")
    args = parser.parse_args()
    d = Device(args.serial)
    if args.stage == "inspect":
        print(d.labels(d.nodes()))
    elif args.stage == "login":
        d.start()
        d.find("Адрес сервера")
        d.field(0, "http://127.0.0.1:18763")
        d.field(1, "readerone")
        d.field(2, os.environ["POLKA_QA_PASSWORD"])
        d.adb("shell", "input", "keyevent", "4")
        d.find("Подключиться", tap=True)
        d.find("Продолжить чтение")
        print("Login and native home passed")
    elif args.stage == "epub":
        d.find("Библиотека", tap=True)
        d.find("Северная полка", tap=True)
        d.find("Скачать", tap=True)
        d.find("Доступна офлайн")
        d.find("Читать", tap=True)
        d.find("Первая глава")
        d.find("Настройки текста", tap=True)
        d.find("Увеличить · Размер шрифта", tap=True)
        d.find("Готово", tap=True)
        d.rotate("1")
        d.find("Первая глава")
        d.rotate("0")
        d.find("Первая глава")
        d.visible_epub_text("Пробная книга для проверки читалки.")
        print("EPUB restored after font change and rotation")
    elif args.stage == "offline":
        try:
            d.airplane(True)
            d.find("Следующая страница", tap=True)
            d.find("Вторая глава")
            d.find("ожидает отправки")
            d.restart()
            candidates = [n for n in d.nodes() if n.get("text") == "Продолжить чтение"]
            if not candidates:
                d.find("Продолжить чтение")
                candidates = [n for n in d.nodes() if n.get("text") == "Продолжить чтение"]
            d.tap(candidates[-1])
            d.find("Вторая глава")
            d.visible_epub_text("Проверка восстановления позиции.")
            print("Offline cold start restored the second chapter")
        finally:
            d.airplane(False)
        d.find("Синхронизировано", timeout=65)
        print("Reconnected and uploaded Android position")
    elif args.stage == "pdf":
        d.find("Выйти из книги", tap=True)
        if "Карточка книги" in d.labels(d.nodes()):
            d.adb("shell", "input", "keyevent", "4")
        d.find("Библиотека", tap=True)
        d.find("Атлас для проверки", tap=True)
        d.find("Скачать", tap=True)
        d.find("Доступна офлайн")
        d.find("Читать", tap=True)
        d.find("Перейти к странице", tap=True)
        d.field(0, "17")
        d.adb("shell", "input", "keyevent", "4")
        nodes = d.nodes()
        target = next(n for n in nodes if n.get("text") == "Перейти")
        d.tap(target)
        d.find("Стр. 17 из 24")
        d.find("Синхронизировано")
        print("Phone saved PDF page 17")


if __name__ == "__main__":
    main()
