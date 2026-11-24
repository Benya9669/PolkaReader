"""Run LiveDataTest against serve_fixture.py on a dedicated, already-installed emulator."""
import argparse
import getpass
import os
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Укажите отдельный тестовый эмулятор")
    command = [args.adb, "-s", args.serial]
    password = os.environ.get("POLKA_QA_PASSWORD") or getpass.getpass("Пароль тестового сервера: ")
    port = "tcp:18763"
    mappings = subprocess.check_output(command + ["reverse", "--list"], text=True).splitlines()
    mapped = [line.split()[-1] for line in mappings if len(line.split()) >= 2 and line.split()[-2] == port]
    if mapped and mapped != [port]:
        parser.error("Порт эмулятора 18763 уже перенаправлен на другой порт")
    if not mapped:
        subprocess.run(command + ["reverse", port, port], check=True)
    try:
        # The credential goes to run-as over stdin and never appears in a process argument.
        subprocess.run(command + ["shell", "-T", "run-as ru.polka.reader sh -c 'umask 077; mkdir -p cache && cat > cache/polka-live-qa'"],
                       input=password.encode("utf-8"), check=True)
        result = subprocess.run(command + ["shell", "am", "instrument", "-w", "-e", "class",
                                "ru.polka.reader.data.LiveDataTest",
                                "ru.polka.reader.test/androidx.test.runner.AndroidJUnitRunner"],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        report = result.stdout.decode("utf-8", errors="replace")
        print(report)
        return 0 if result.returncode == 0 and "OK (1 test)" in report else 1
    finally:
        subprocess.run(command + ["shell", "run-as", "ru.polka.reader", "rm", "-f", "cache/polka-live-qa"], check=True)
        if not mapped:
            subprocess.run(command + ["reverse", "--remove", port], check=True)


if __name__ == "__main__":
    sys.exit(main())
