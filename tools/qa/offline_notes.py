"""Verify Android's persistent note queue on a dedicated emulator and serve_fixture.py."""
import argparse
import json
import os
import re
import subprocess
import time

import httpx


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--host-port", required=True, type=int)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not 1 <= args.host_port <= 65535:
        parser.error("Нужны отдельный тестовый эмулятор и действительный порт")
    command = [args.adb, "-s", args.serial]

    def adb(*flags: str, payload: bytes | None = None) -> str:
        return subprocess.check_output(command + list(flags), input=payload).decode("utf-8", errors="replace")

    def instrument(stage: str) -> None:
        report = adb("shell", "am", "instrument", "-w", "-e", "class", "ru.polka.reader.data.OfflineNotesTest",
                     "-e", "notesStage", stage, "ru.polka.reader.test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in report:
            raise RuntimeError(report)

    password = os.environ["POLKA_QA_PASSWORD"]
    port, host = "tcp:18763", f"tcp:{args.host_port}"
    previous = [line.split()[-1] for line in adb("reverse", "--list").splitlines()
                if len(line.split()) >= 2 and line.split()[-2] == port]
    if previous and previous != [host]:
        parser.error("У этого эмулятора уже есть другой стенд на порту 18763")
    original_airplane = adb("shell", "settings", "get", "global", "airplane_mode_on").strip()
    if original_airplane != "0":
        parser.error("Перед запуском выключите авиарежим тестового эмулятора")
    with httpx.Client(base_url=f"http://127.0.0.1:{args.host_port}/api/v1/", trust_env=False) as api:
        api.post("auth/login", json={"username": "readerone", "password": password}).raise_for_status()
        try:
            adb("reverse", port, host)
            adb("shell", "-T", "run-as ru.polka.reader sh -c 'umask 077; mkdir -p cache && cat > cache/notes-qa-login'",
                payload=password.encode())
            instrument("prepare")
            state = json.loads(adb("shell", "run-as", "ru.polka.reader", "cat", "cache/notes-qa-state.json"))
            endpoint = "reading/annotations/" + str(state["file"])
            adb("reverse", "--remove", port)
            adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
            instrument("offline")
            adb("shell", "am", "force-stop", "ru.polka.reader")
            instrument("restore")
            assert all(n["quote"] != state["quote"] for n in api.get(endpoint).raise_for_status().json())
            adb("shell", "am", "start", "-n", "ru.polka.reader/.MainActivity")
            adb("reverse", port, host)
            adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                found = [n for n in api.get(endpoint).raise_for_status().json() if n["quote"] == state["quote"]]
                if found:
                    assert len(found) == 1 and found[0]["note"] == "Заметка из авиарежима"
                    print("Offline note restored after process stop and synchronized once on reconnect.")
                    return
                time.sleep(1)
            raise AssertionError("Очередь не отправила тестовую заметку")
        finally:
            adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
            if previous:
                adb("reverse", port, host)
            elif port in adb("reverse", "--list"):
                adb("reverse", "--remove", port)
            api.post("auth/logout").raise_for_status()


if __name__ == "__main__":
    main()
