"""Check deliberate backwards navigation on two dedicated emulators with a shared PDF."""
import argparse

from phone_flow import Device


def go_to(device: Device, page: int) -> None:
    device.find("Перейти к странице", tap=True)
    device.field(0, str(page))
    device.adb("shell", "input", "keyevent", "4")
    node = next(n for n in device.nodes() if n.get("text") == "Перейти")
    device.tap(node)
    device.find(f"Стр. {page} из 24")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phone", default="emulator-5582")
    parser.add_argument("--tablet", default="emulator-5584")
    args = parser.parse_args()
    if args.phone == args.tablet:
        raise ValueError("Требуются два эмулятора")
    phone, tablet = Device(args.phone), Device(args.tablet)
    phone.find("Стр. 17 из 24")
    tablet.find("Стр. 17 из 24")
    try:
        phone.airplane(True)
        tablet.airplane(True)
        go_to(phone, 14)
        go_to(tablet, 9)
        phone.find("ожидает отправки")
        tablet.find("ожидает отправки")
        phone.airplane(False)
        phone.find("Синхронизировано", timeout=65)
        tablet.airplane(False)
        tablet.find("Где продолжить чтение?", timeout=65)
        tablet.find("Страница 14")
        tablet.find("Страница 9")
        tablet.find("Продолжить с этого устройства", tap=True)
        tablet.find("Синхронизировано", timeout=65)
        phone.find("Выйти из книги", tap=True)
        phone.find("Читать", tap=True)
        phone.find("Стр. 9 из 24")
        print("Conflict resolved: tablet page 9 persisted and opened on phone")
    finally:
        tablet.airplane(False)
        phone.airplane(False)


if __name__ == "__main__":
    main()
