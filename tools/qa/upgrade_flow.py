"""Verify an in-place signed update on a fresh emulator with production Android storage."""
import argparse
import os
from pathlib import Path

import httpx

from phone_flow import Device


def menu(device: Device, name: str) -> None:
    device.find("Учётная запись и оформление", tap=True)
    device.find(name, tap=True)


def assert_chip(device: Device, label: str) -> None:
    nodes = device.nodes()
    assert any((n.get("selected") == "true" or n.get("checked") == "true")
               and any(c.get("text") == label for c in n.iter("node")) for n in nodes), "Не сохранена тема страницы"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    d = Device(args.serial)
    package = "ru.polka.reader"
    assert package not in d.adb("shell", "pm", "list", "packages", package), "Требуется отдельный чистый эмулятор"
    with httpx.Client(base_url="http://127.0.0.1:18763/api/v1/") as reader:
        reader.post("auth/login", json={"username": "readertwo", "password": os.environ["POLKA_QA_PASSWORD"]}).raise_for_status()
        book = reader.get("books", params={"q": "Северная полка"}).raise_for_status().json()["items"][0]
        f = next(f for e in book["editions"] for f in e["files"] if f["format"] == "epub")
        endpoint = "reading/progress/" + str(f["id"])
        # Bring the dedicated fixture account to a known web locator at the start of chapter one.
        version = reader.get(endpoint).raise_for_status().json()["version"]
        initial = reader.put(endpoint, json={"version": version, "device": "Проверка обновления",
                             "locator": "epubcfi(/6/2[one]!/4/2/1:0)", "percentage": 0.0,
                             "chapter": "Первая глава"}).raise_for_status().json()
        try:
            assert "Success" in d.adb("install", str(args.artifacts / "Polka-Reader-0.1.0.apk"))
            d.adb("reverse", "tcp:18763", "tcp:18763")
            d.start()
            d.find("Адрес сервера")
            d.field(0, "http://127.0.0.1:18763")
            d.field(1, "readertwo")
            d.field(2, os.environ["POLKA_QA_PASSWORD"])
            d.adb("shell", "input", "keyevent", "4")
            d.find("Подключиться", tap=True)
            menu(d, "Настройки")
            d.find("Тёмная", tap=True)
            d.find("✓  Тёмная")
            d.find("Готово", tap=True)
            menu(d, "О приложении")
            d.find("Версия 0.1.0")
            d.find("Закрыть", tap=True)
            d.find("Библиотека", tap=True)
            d.find("Северная полка", tap=True)
            d.find("Скачать", tap=True)
            d.find("Доступна офлайн")
            d.find("Читать", tap=True)
            d.find("Первая глава")
            d.find("Настройки текста", tap=True)
            d.find("Увеличить · Размер шрифта", tap=True)
            d.find("Размер шрифта · 120%")
            d.find("Сепия", tap=True)
            assert_chip(d, "Сепия")
            d.find("Готово", tap=True)
            d.airplane(True)
            d.find("Следующая страница", tap=True)
            d.find("Вторая глава")
            d.find("ожидает отправки")
            assert reader.get(endpoint).raise_for_status().json()["version"] == initial["version"]
            print("0.1.0 ready: session, download, offline second chapter, two appearance settings.", flush=True)
            assert "Success" in d.adb("install", "-r", str(args.artifacts / "Polka-Reader-0.1.1.apk"))
            d.start()
            d.find("Северная полка")
            menu(d, "О приложении")
            d.find("Версия 0.1.1")
            d.find("Закрыть", tap=True)
            menu(d, "Настройки")
            d.find("readertwo")
            d.find("✓  Тёмная")
            d.find("Готово", tap=True)
            buttons = [n for n in d.nodes() if n.get("text") == "Продолжить чтение"]
            assert buttons, "Нет действия продолжения чтения"
            d.tap(buttons[-1])
            d.find("Вторая глава")
            d.find("ожидает отправки")
            d.find("Настройки текста", tap=True)
            d.find("Размер шрифта · 120%")
            assert_chip(d, "Сепия")
            d.find("Готово", tap=True)
            assert reader.get(endpoint).raise_for_status().json()["version"] == initial["version"]
            d.airplane(False)
            d.find("Синхронизировано", timeout=80)
            accepted = reader.get(endpoint).raise_for_status().json()
            assert accepted["version"] > initial["version"] and accepted["chapter"] == "Вторая глава"
            assert accepted["locator"].startswith("epubcfi(") and accepted["locator"] != initial["locator"]
            assert accepted["device"].startswith("Android")
            print("0.1.1 kept account and session, offline EPUB, exact chapter, outbox, dark app, sepia and 120% font.")
            print("Existing session uploaded the queued CFI to v0.3.0 without logging in again.")
        finally:
            d.airplane(False)
            reader.post("auth/logout").raise_for_status()


if __name__ == "__main__":
    main()
