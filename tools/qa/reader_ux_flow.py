"""Exercise the installed reader using the local library from reader_ux_fixture.py."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time

import httpx
from phone_flow import Device
from reader_ux_fixture import CHAPTERS, EPUB_TITLE, SUMMARY


def exact(device: Device, text: str, tap: bool = False, last: bool = False):
    end = time.monotonic() + 20
    while time.monotonic() < end:
        matches = [n for n in device.nodes() if text in (n.get("text"), n.get("content-desc"))]
        if matches:
            target = matches[-1] if last else matches[0]
            if tap:
                device.tap(target)
            return target
    raise AssertionError("Элемент недоступен: " + text)


def capture(device: Device, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(subprocess.check_output(device.target + ["exec-out", "screencap", "-p"]))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["open", "style", "note", "navigate", "resume", "motion"])
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, default=18764)
    ap.add_argument("--username", default="readerone")
    ap.add_argument("--shots", type=Path, default=Path(".verification/reader-ux"))
    args = ap.parse_args()
    d = Device(args.serial)
    with httpx.Client(base_url=f"http://127.0.0.1:{args.port}/api/v1/", trust_env=False) as api:
        api.post("auth/login", json={"username": args.username, "password": os.environ["POLKA_QA_PASSWORD"]}).raise_for_status()
        try:
            works = api.get("books", params={"q": EPUB_TITLE}).raise_for_status().json()["items"]
            file = next(w for w in works if w["title"] == EPUB_TITLE)["editions"][0]["files"][0]
            endpoint = "reading/progress/" + str(file["id"])
            def position():
                return api.get(endpoint).raise_for_status().json()

            if args.stage == "open":
                d.restart()
                exact(d, "Библиотека", tap=True)
                exact(d, EPUB_TITLE, tap=True)
                d.find(SUMMARY)
                exact(d, "Скачать")
                capture(d, args.shots / "card.png")
                exact(d, "Читать", tap=True)
                d.find("Перелистывание")
                d.find(CHAPTERS[0])
                capture(d, args.shots / "spread.png")
                print("Opened EPUB with an edition cover and summary, using temporary storage.")
            elif args.stage == "style":
                before = position()
                exact(d, "Настройки текста", tap=True)
                exact(d, "Перед сном", tap=True)
                d.find("120%")
                d.find("Увеличить · Размер шрифта", tap=True)
                d.find("130%")
                capture(d, args.shots / "night-profile.png")
                exact(d, "День", tap=True)
                d.find("110%")
                exact(d, "Перед сном", tap=True)
                d.find("130%")
                exact(d, "День", tap=True)
                exact(d, "Готово", tap=True)
                for mode in ("Загиб страницы", "Без анимации", "Сдвиг"):
                    exact(d, "Перелистывание", tap=True)
                    exact(d, mode, tap=True)
                    exact(d, "Готово", tap=True)
                assert position()["version"] == before["version"]
                print("Both independent appearance profiles and motion choices preserved the original position.")
            elif args.stage == "note":
                before = position()
                exact(d, "Примечание", tap=True)
                exact(d, "Сноска")
                d.find("Створ — линия двух береговых знаков.")
                capture(d, args.shots / "footnote.png")
                exact(d, "Закрыть", tap=True)
                assert position()["version"] == before["version"]
                exact(d, "Посмотреть карту", tap=True)
                exact(d, "Вернуться к месту чтения", tap=True)
                d.find(CHAPTERS[0])
                print("Cross-chapter footnote opened without a progress write; an ordinary link offered a return.")
            elif args.stage == "navigate":
                # Begin with a real position, including a point inside this chapter.
                exact(d, "Следующая страница", tap=True)
                d.find("Синхронизировано")
                time.sleep(2)
                origin = position()
                assert origin.get("locator", "").startswith("epubcfi(")
                exact(d, "Оглавление", tap=True)
                exact(d, CHAPTERS[2], tap=True)
                if "Закрыть оглавление" in d.labels(d.nodes()):
                    exact(d, "Закрыть оглавление", tap=True)
                exact(d, "Вернуться к месту чтения")
                d.find(CHAPTERS[2])
                time.sleep(2)
                assert position()["locator"] != origin["locator"]
                exact(d, "Вернуться к месту чтения", tap=True)
                end = time.monotonic() + 20
                while time.monotonic() < end and position()["locator"] != origin["locator"]:
                    time.sleep(.25)
                assert position()["locator"] == origin["locator"]
                print("TOC jump and return restored and uploaded the exact original EPUB CFI.")
            elif args.stage == "motion":
                for mode in ("Сдвиг", "Загиб страницы", "Без анимации"):
                    exact(d, "Перелистывание", tap=True)
                    exact(d, mode, tap=True)
                    exact(d, "Готово", tap=True)
                    exact(d, "Скрыть панели", tap=True)
                    view = next(n for n in d.nodes() if n.get("class") == "android.webkit.WebView")
                    l, t, r, b = map(int, re.findall(r"\d+", view.get("bounds")))
                    def x(fraction):
                        return str(round(l + fraction * (r - l)))
                    y = str((b + t) // 2)
                    before = position()
                    d.adb("shell", "input", "swipe", x(.55), y, x(.51), y, "550")
                    time.sleep(2)
                    assert position()["version"] == before["version"], "An aborted drag updated progress"
                    d.adb("shell", "input", "tap", x(.86), y)
                    end = time.monotonic() + 18
                    while time.monotonic() < end and position()["version"] == before["version"]:
                        time.sleep(.3)
                    assert position()["version"] == before["version"] + 1, "One edge tap must commit once"
                    d.adb("shell", "input", "tap", x(.50), y)
                    exact(d, "Перелистывание")
                before = position()
                d.rotate("1")
                d.find("Перелистывание")
                d.rotate("0")
                d.find("Перелистывание")
                assert position()["version"] == before["version"]
                setting = "animator_duration_scale"
                previous = d.adb("shell", "settings", "get", "global", setting)
                try:
                    d.adb("shell", "settings", "put", "global", setting, "0")
                    exact(d, "Перелистывание", tap=True)
                    d.find("Анимация отключена")
                    exact(d, "Готово", tap=True)
                finally:
                    if previous == "null":
                        d.adb("shell", "settings", "delete", "global", setting)
                    else:
                        d.adb("shell", "settings", "put", "global", setting, previous)
                print("All three modes: cancelled drags, edge and centre taps, rotation and Android reduced motion passed.")
            else:
                origin = position()
                exact(d, "Выйти из книги", tap=True)
                exact(d, "На устройстве", tap=True)
                assert EPUB_TITLE not in d.labels(d.nodes())
                d.restart()
                exact(d, "Главная", tap=True)
                exact(d, "Продолжить чтение", tap=True, last=True)
                d.find("Перелистывание")
                d.find(origin["chapter"])
                assert position()["locator"] == origin["locator"]
                print("Force-stop and Home reopened the same file; temporary reading stayed outside downloads.")
        finally:
            api.post("auth/logout").raise_for_status()


if __name__ == "__main__":
    main()
