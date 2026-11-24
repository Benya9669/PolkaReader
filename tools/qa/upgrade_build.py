"""Build two real release APKs in an isolated copy, with one disposable QA signing key."""
import argparse
import os
import re
import secrets
import shutil
import subprocess
import tempfile
from pathlib import Path


def build_pair(project: Path, staging: Path) -> None:
    if (project / "VERSION").read_text().strip() != "0.1.0":
        raise ValueError("This acceptance fixture starts at 0.1.0")
    staging.mkdir(parents=True, exist_ok=False)
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "VERSION"):
        shutil.copy2(project / name, staging / name)
    if (project / "local.properties").is_file():
        shutil.copy2(project / "local.properties", staging / "local.properties")
    shutil.copytree(project / "gradle", staging / "gradle")
    shutil.copytree(project / "app/src/main", staging / "app/src/main")
    shutil.copy2(project / "app/build.gradle.kts", staging / "app/build.gradle.kts")
    artifacts = staging / "release-artifacts"
    artifacts.mkdir()
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    android = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    win = os.name == "nt"
    wrapper = staging / ("gradlew.bat" if win else "gradlew")
    signing_tool = android / ("apksigner.bat" if win else "apksigner")
    aapt = android / ("aapt2.exe" if win else "aapt2")
    certificate = None
    with tempfile.TemporaryDirectory(prefix="PolkaUpgradeQA-") as scratch:
        store = Path(scratch) / "test-only.jks"
        signing_env = {**os.environ, "ANDROID_KEYSTORE_PATH": str(store), "ANDROID_KEY_ALIAS": "temporary-qa",
                       "ANDROID_KEYSTORE_PASSWORD": secrets.token_hex(24), "ANDROID_KEY_PASSWORD": secrets.token_hex(24)}
        subprocess.run([java / ("keytool.exe" if win else "keytool"), "-genkeypair", "-keystore", store,
                        "-storetype", "JKS", "-alias", "temporary-qa", "-keyalg", "RSA", "-validity", "30",
                        "-dname", "CN=Disposable QA", "-storepass:env", "ANDROID_KEYSTORE_PASSWORD",
                        "-keypass:env", "ANDROID_KEY_PASSWORD"], env=signing_env, check=True)
        for version, code in (("0.1.0", 1000), ("0.1.1", 1001)):
            (staging / "VERSION").write_text(version + "\n", encoding="utf-8")
            with (staging / ("build-" + version + ".log")).open("wb") as log:
                subprocess.run([wrapper, "--no-daemon", "--max-workers=2", ":app:assembleRelease"],
                               cwd=staging, env=signing_env, check=True, stdout=log, stderr=subprocess.STDOUT)
            package = artifacts / f"Polka-Reader-{version}.apk"
            shutil.copy2(staging / "app/build/outputs/apk/release/app-release.apk", package)
            signature = subprocess.check_output([signing_tool, "verify", "--verbose", "--print-certs", package],
                                                text=True, encoding="utf-8")
            current = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature).group(1)
            if certificate is not None and current != certificate:
                raise AssertionError("APK signer changed")
            certificate = current
            manifest = subprocess.check_output([aapt, "dump", "badging", package], text=True, encoding="utf-8")
            assert f"package: name='ru.polka.reader' versionCode='{code}' versionName='{version}'" in manifest
            print(f"Verified ru.polka.reader {version} ({code}) using a temporary QA signer.", flush=True)
    print("Both signatures verified, same certificate. Disposable keystore removed.", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("staging", type=Path, help="New directory for the isolated project and outputs")
    args = parser.parse_args()
    build_pair(Path(__file__).resolve().parents[2], args.staging.resolve())
