"""Run the tablet stages alongside phone_flow.py on a separate synthetic fixture emulator."""
import argparse
from phone_flow import Device


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["prepare", "read"])
    parser.add_argument("--serial", default="emulator-5584")
    args = parser.parse_args()
    d = Device(args.serial)
    if args.stage == "prepare":
        d.find("Библиотека", tap=True)
        d.find("Атлас для проверки", tap=True)
        d.find("Скачать")
        d.rotate("1")
        d.find("Скачать")
        d.rotate("0")
        d.find("Название или автор")
        d.find("Скачать", tap=True)
        d.find("Доступна офлайн")
        print("Verified tablet catalog and detail across rotation. Downloaded PDF.")
    else:
        d.find("Читать", tap=True)
        d.find("Стр. 17 из 24")
        d.rotate("1")
        d.find("Стр. 17 из 24")
        d.rotate("0")
        d.find("Стр. 17 из 24")
        print("Restored phone page 17 on tablet and kept it in both orientations.")


if __name__ == "__main__":
    main()
