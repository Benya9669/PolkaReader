"""Verify cross-edition progress with the isolated v0.3.0 fixture server and tablet UI."""
import argparse
import io
import os

import httpx
from pypdf import PdfWriter

from phone_flow import Device

ORIGIN = "http://127.0.0.1:18763/api/v1"
CASE = os.environ.get("POLKA_QA_CASE", "").strip()
LIBRARY = ("Проверка изданий " + CASE).strip()
TITLE = ("Место между изданиями " + CASE).strip()
TARGET, SOURCE = "alternate-20.pdf", "alternate-8.pdf"


def pdf(pages: int) -> bytes:
    document = PdfWriter()
    for _ in range(pages):
        document.add_blank_page(width=320, height=480)
    document.add_metadata({"/Title": TITLE, "/Author": "Проверка"})
    output = io.BytesIO()
    document.write(output)
    return output.getvalue()


def login(client: httpx.Client, name: str) -> None:
    client.post("auth/login", json={"username": name, "password": os.environ["POLKA_QA_PASSWORD"]}).raise_for_status()


def book_files(client: httpx.Client, library_id: int) -> dict[str, int]:
    books = client.get("books", params={"library_id": library_id, "q": TITLE}).raise_for_status().json()["items"]
    return {file["name"]: file["id"] for book in books for edition in book["editions"] for file in edition["files"]}


def seed() -> None:
    with httpx.Client(base_url=ORIGIN + "/") as admin:
        login(admin, "qaadmin")
        libraries = admin.get("libraries").raise_for_status().json()
        library = next((lib for lib in libraries if lib["name"] == LIBRARY), None)
        if library is None:
            library = admin.post("libraries", json={"name": LIBRARY, "mode": "managed", "shared": True}).raise_for_status().json()
        ident = library["id"]
        files = book_files(admin, ident)
        target = files.get(TARGET)
        if target is None:
            result = admin.post(f"libraries/{ident}/upload", files={"file": (TARGET, pdf(20))}).raise_for_status().json()
            target = result["file"]["id"]
        if SOURCE not in files:
            work = admin.get(f"files/{target}/info").raise_for_status().json()["work_id"]
            admin.post(f"books/{work}/editions/upload", files={"file": (SOURCE, pdf(8))}).raise_for_status()
        admin.post("auth/logout").raise_for_status()
    with httpx.Client(base_url=ORIGIN + "/") as reader:
        login(reader, "readertwo")
        files = book_files(reader, ident)
        endpoint = "reading/progress/" + str(files[SOURCE])
        version = reader.get(endpoint).raise_for_status().json()["version"]
        reader.put(endpoint, json={"version": version, "device": "Тестовое издание",
                                   "locator": "6", "percentage": 0.75}).raise_for_status()
        alternate = reader.get("reading/progress/" + str(files[TARGET])).raise_for_status().json()["alternate"]
        assert alternate["file_id"] == files[SOURCE] and alternate["percentage"] == 0.75
        reader.post("auth/logout").raise_for_status()
    print("Seeded a separate work; v0.3.0 returned alternate for its 20-page edition.")


def verify(device: Device) -> None:
    device.restart()
    device.find("Учётная запись и оформление", tap=True)
    if "readertwo" in device.labels(device.nodes()):
        device.adb("shell", "input", "keyevent", "4")
    else:
        device.find("Сменить аккаунт", tap=True)
        device.field(1, "readertwo")
        device.field(2, os.environ["POLKA_QA_PASSWORD"])
        device.adb("shell", "input", "keyevent", "4")
        candidates = [n for n in device.nodes() if n.get("text") in {"Войти", "Подключиться"}]
        device.tap(candidates[0])
    device.find("Продолжить чтение")
    device.find("Библиотека", tap=True)
    device.find(TITLE, tap=True)
    device.find(TARGET)
    labels = device.labels(device.nodes())
    if "Читать" not in labels:
        device.find("Скачать", tap=True)
        device.find("Доступна офлайн")
    with httpx.Client(base_url=ORIGIN + "/") as reader:
        login(reader, "readertwo")
        library = next(lib["id"] for lib in reader.get("libraries").raise_for_status().json() if lib["name"] == LIBRARY)
        files = book_files(reader, library)
        path = "reading/progress/" + str(files[TARGET])
        before = reader.get(path).raise_for_status().json()
        assert before["alternate"]["file_id"] == files[SOURCE]
        device.find("Читать", tap=True)
        device.find("Позиция другого файла")
        device.find("75 %")
        assert before["version"] == reader.get(path).raise_for_status().json()["version"]
        button = next(n for n in device.nodes() if n.get("text") == "Перейти")
        device.tap(button)
        device.find("Стр. 15 из 20")
        device.find("Синхронизировано")
        accepted = reader.get(path).raise_for_status().json()
        assert accepted["locator_type"] == "pdf_page" and accepted["locator"] == "15"
        assert accepted["percentage"] == 0.75 and accepted["version"] > before["version"]
        assert reader.get("reading/progress/" + str(files[SOURCE])).raise_for_status().json()["locator"] == "6"
        reader.post("auth/logout").raise_for_status()
    print("Android confirmed alternate and saved page 15/20 for the new file; source remained at 6/8.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["seed", "verify"])
    parser.add_argument("--serial", default="emulator-5584")
    args = parser.parse_args()
    seed() if args.stage == "seed" else verify(Device(args.serial))
