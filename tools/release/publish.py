"""Publish a signed Polka Reader APK from the checked-out, tested main commit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


def version_code(version: str) -> int:
    match = re.fullmatch(r"(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})", version)
    if not match:
        raise ValueError("Недопустимый формат VERSION")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if major > 2099 or code == 0:
        raise ValueError("VERSION вне диапазона Android")
    return code


def candidate(event: str, ref: str, before: str | None, version: str) -> bool:
    if ref != "refs/heads/main" or event not in {"push", "workflow_dispatch"}:
        return False
    if event == "workflow_dispatch" or before == "0" * 40:
        return True
    if not before or not re.fullmatch("[0-9a-f]{40}", before):
        raise ValueError("В событии push отсутствует предыдущий commit")
    if not subprocess.check_output(["git", "ls-tree", before, "--", "VERSION"]):
        return True
    old = subprocess.check_output(["git", "show", before + ":VERSION"]).decode().rstrip("\r\n")
    return old != version


def digest(file: Path) -> str:
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def apk_name(version: str) -> str:
    return f"Polka-Reader-{version}.apk"


def checksum(name: str, sha256: str) -> bytes:
    return f"{sha256}  {name}\n".encode("ascii")


def verify_apk(file: Path, version: str) -> str:
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    signatures = subprocess.check_output([tools / "apksigner", "verify", "--print-certs", file], text=True)
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signatures, re.M)
    if len(signers) != 1:
        raise ValueError("Ожидается один сертификат подписи APK")
    metadata = subprocess.check_output([tools / "aapt2", "dump", "badging", file], text=True)
    package = next((line for line in metadata.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"([\w]+)='([^']*)'", package))
    if (fields.get("name") != "ru.polka.reader" or fields.get("versionName") != version
            or fields.get("versionCode") != str(version_code(version)) or fields.get("split")
            or "application-debuggable" in metadata.splitlines()):
        raise ValueError("APK не соответствует release-пакету, versionName или versionCode")
    return signers[0].lower()


class GitHub:
    def __init__(self, repo: str):
        self.base = f"repos/{repo}"

    def api(self, path: str, method: str = "GET", body: dict | None = None, paginate: bool = False):
        args = ["gh", "api", "-X", method, self.base + "/" + path]
        if method == "GET":
            args += ["-H", "Cache-Control: no-cache"]
        if paginate:
            args += ["--paginate", "--slurp"]
        if body is not None:
            args += ["--input", "-"]
        result = subprocess.run(args, input=json.dumps(body).encode() if body is not None else None,
                                capture_output=True)
        if result.returncode:
            error = result.stderr.decode(errors="replace").strip()
            status = re.search(r"HTTP (404|408|429|5[0-9]{2})\b", error)
            if method == "GET" and status:
                raise TransientReadError(error, int(status[1]))
            raise ValueError(f"GitHub {method} {path}: {error}")
        result = result.stdout
        return json.loads(result) if result.strip() else None

    def releases(self) -> list[dict]:
        return [r for page in self.api("releases?per_page=100", paginate=True) for r in page]

    def release(self, release_id: int) -> dict:
        return self.api(f"releases/{release_id}")

    def release_by_tag(self, tag: str) -> dict | None:
        try:
            return self.api("releases/tags/" + tag)
        except TransientReadError as error:
            if error.status == 404:
                return None
            raise

    def tag_commit(self, tag: str) -> str | None:
        items = self.api("git/matching-refs/tags/" + tag)
        obj = next((r["object"] for r in items if r["ref"] == "refs/tags/" + tag), None)
        if obj is None:
            return None
        while obj["type"] == "tag":
            obj = self.api("git/tags/" + obj["sha"])["object"]
        if obj["type"] != "commit":
            raise ValueError("Тег не указывает на commit")
        return obj["sha"]

    def upload(self, release_id: int, name: str, path: Path) -> None:
        url = f"https://uploads.github.com/{self.base}/releases/{release_id}/assets?name={name}"
        subprocess.run(["gh", "api", "-X", "POST", url, "-H", "Content-Type: application/octet-stream",
                        "-H", f"Content-Length: {path.stat().st_size}", "--input", str(path), "--silent"], check=True)

    def download(self, asset: dict, path: Path) -> None:
        with path.open("wb") as stream:
            subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                            f"{self.base}/releases/assets/{asset['id']}"], stdout=stream, check=True)
        if "sha256:" + digest(path) != asset.get("digest"):
            raise ValueError("Контрольная сумма скачанного файла отличается от метаданных GitHub")


class TransientReadError(OSError):
    """An idempotent read can be temporarily unavailable after a successful write."""
    def __init__(self, message: str, status: int):
        super().__init__(message)
        self.status = status


def wait_for_read(read, description):
    last_error = None
    for wait in (0, 0.5, 1, 2, 4, 6, 8):
        if wait:
            time.sleep(wait)
        try:
            value = read()
            if value is not None:
                return value
        except TransientReadError as error:
            last_error = error
    raise ValueError(f"GitHub не подтвердил {description}: {last_error or 'истекло время ожидания'}")


def verified_asset(assets: list[dict], name: str) -> dict:
    matches = [asset for asset in assets if asset["name"] == name]
    if len(matches) != 1 or matches[0]["state"] != "uploaded" or matches[0]["size"] <= 0:
        raise ValueError(f"В релизе отсутствует полностью загруженный {name}")
    asset = matches[0]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest") or ""):
        raise ValueError(f"GitHub не подтвердил SHA-256 файла {name}")
    return asset


def complete(release: dict, version: str) -> dict:
    name = apk_name(version)
    apk = verified_asset(release["assets"], name)
    sha_file = verified_asset(release["assets"], name + ".sha256")
    expected = checksum(name, apk["digest"].removeprefix("sha256:"))
    if sha_file["digest"] != "sha256:" + hashlib.sha256(expected).hexdigest():
        raise ValueError("Опубликованная сумма не соответствует APK")
    return apk


class Publisher:
    def __init__(self, github: GitHub, version: str, commit: str):
        self.api = github
        self.version = version
        self.tag = "v" + version
        self.title = "Polka Reader " + self.tag
        self.commit = commit
        self.marker = "<!-- polka-reader-release " + commit + " -->"

    def inspect(self) -> tuple[bool, dict | None]:
        # The release list also includes drafts whose public tag has not been created.
        ids = {r["id"] for r in self.api.releases() if self.matches_tag(r)}
        by_tag = self.api.release_by_tag(self.tag)
        if by_tag:
            ids.add(by_tag["id"])
        if len(ids) > 1:
            raise ValueError("Обнаружены несколько релизов с одинаковым тегом")
        release = None
        if ids:
            release_id = ids.pop()
            release = wait_for_read(lambda: self.api.release(release_id), f"метаданные релиза {release_id}")
        tagged = self.api.tag_commit(self.tag)
        if release and not release["draft"]:
            if release["tag_name"] != self.tag:
                raise ValueError("Опубликованный тег изменился во время проверки")
            complete(release, self.version)
            if not tagged:
                raise ValueError("У опубликованного релиза отсутствует тег")
            target = release["target_commitish"]
            if re.fullmatch(r"[0-9a-f]{40}", target) and tagged != target:
                raise ValueError("Тег опубликованного релиза был перемещён")
            return False, release
        if tagged and tagged != self.commit:
            raise ValueError(f"Тег {self.tag} уже указывает на другой commit {tagged}")
        if release:
            self.check_owner(release)
        return True, release

    def matches_tag(self, release: dict) -> bool:
        return release["tag_name"] == self.tag or (
            release["draft"] and release["name"] == self.title and
            bool(re.fullmatch(r"untagged-[0-9a-f]+", release["tag_name"] or ""))
        )

    def check_owner(self, release: dict) -> None:
        if (not self.matches_tag(release) or release["target_commitish"] != self.commit
                or release["name"] != self.title or release["author"]["login"] != "github-actions[bot]"
                or self.marker not in (release["body"] or "").splitlines()):
            raise ValueError("Черновик принадлежит другому запуску или commit")

    def check_upgrade(self, signer: str, folder: Path) -> None:
        previous = []
        for release in self.api.releases():
            tag = release["tag_name"]
            if release["draft"] or release["prerelease"] or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
                continue
            if tag == self.tag:
                continue
            code = version_code(tag[1:])
            if code >= version_code(self.version):
                raise ValueError(f"versionCode должен быть больше уже опубликованного {tag}")
            previous.append((code, release))
        if previous:
            release = max(previous, key=lambda entry: entry[0])[1]
            version = release["tag_name"][1:]
            file = folder / apk_name(version)
            self.api.download(complete(release, version), file)
            if verify_apk(file, version) != signer:
                raise ValueError("Сертификат подписи отличается от предыдущего выпуска")

    def publish(self, apk: Path, folder: Path) -> None:
        needed, draft = self.inspect()
        if not needed:
            return
        signer = verify_apk(apk, self.version)
        self.check_upgrade(signer, folder)
        if draft is None:
            draft = self.api.api("releases", "POST", {
                "tag_name": self.tag, "name": self.title, "target_commitish": self.commit,
                "draft": True, "generate_release_notes": True, "body": self.marker
            })
        path = "releases/" + str(draft["id"])
        name = apk_name(self.version)

        def current(ready=None, published=False) -> dict:
            def read():
                # A new draft can be absent from the releases list even after a successful POST.
                release = self.api.release(draft["id"])
                if release["id"] != draft["id"]:
                    raise ValueError("GitHub вернул другой ID релиза")
                self.check_owner(release)
                if release["draft"] and published:
                    return None
                if not release["draft"] and not published:
                    raise ValueError("Релиз уже опубликован, изменение файлов остановлено")
                if ready is not None and not ready(release["assets"]):
                    return None
                return release
            return wait_for_read(read, f"состояние релиза {draft['id']}")

        def uploaded(assets, filename):
            return any(a["name"] == filename and a["state"] == "uploaded" and a.get("digest") for a in assets)

        def writable_assets(filename: str) -> list[dict]:
            assets = current()["assets"]
            for asset in assets:
                # GitHub may leave an empty "starter" after an interrupted upload.
                # Only this incomplete object in our verified draft can be removed.
                if asset["name"] == filename and asset["state"] == "starter" and asset["size"] == 0:
                    self.api.api("releases/assets/" + str(asset["id"]), "DELETE")
                    return current(lambda items: all(item["id"] != asset["id"] for item in items))["assets"]
            return assets

        # Build output can differ between runners. Reuse a previously uploaded signed APK
        # from this exact draft after checking its signature, manifest and remote digest.
        assets = writable_assets(name)
        if any(asset["name"] == name for asset in assets):
            existing = folder / name
            assets = current(lambda items: uploaded(items, name))["assets"]
            self.api.download(verified_asset(assets, name), existing)
            if verify_apk(existing, self.version) != signer:
                raise ValueError("Другая подпись APK в черновике")
            apk = existing
        else:
            self.api.upload(draft["id"], name, apk)
        hash_value = digest(apk)
        assets = current(lambda items: uploaded(items, name))["assets"]
        if verified_asset(assets, name)["digest"] != "sha256:" + hash_value:
            raise ValueError("Загруженный APK отличается от проверенного")
        assets = writable_assets(name + ".sha256")
        if not any(asset["name"] == name + ".sha256" for asset in assets):
            check = folder / (name + ".sha256")
            check.write_bytes(checksum(name, hash_value))
            self.api.upload(draft["id"], check.name, check)
        complete(current(lambda items: uploaded(items, name) and uploaded(items, name + ".sha256")), self.version)
        if self.api.tag_commit(self.tag) is None:
            # Create-only Git ref. A concurrent publisher cannot move an existing tag.
            self.api.api("git/refs", "POST", {"ref": "refs/tags/" + self.tag, "sha": self.commit})
        if wait_for_read(lambda: self.api.tag_commit(self.tag), "появление тега") != self.commit:
            raise ValueError("Тег не совпал с проверенным commit")
        self.api.api(path, "PATCH", {"draft": False, "tag_name": self.tag, "target_commitish": self.commit})
        complete(current(published=True), self.version)
        if self.api.tag_commit(self.tag) != self.commit:
            raise ValueError("GitHub не подтвердил публикацию")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["candidate", "inspect", "publish"])
    parser.add_argument("apk", nargs="?", type=Path)
    args = parser.parse_args()
    version = Path("VERSION").read_text(encoding="ascii").rstrip("\r\n")
    version_code(version)
    if args.command == "candidate":
        before = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8")).get("before")
        result = candidate(os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"], before, version)
        print("candidate=" + str(result).lower())
        return
    if os.environ["GITHUB_REF"] != "refs/heads/main" or os.environ["GITHUB_EVENT_NAME"] not in ("push", "workflow_dispatch"):
        raise SystemExit("Публикация разрешена только из main")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if commit != os.environ["GITHUB_SHA"]:
        raise SystemExit("Checkout не совпадает с проверенным commit")
    publisher = Publisher(GitHub(os.environ["GITHUB_REPOSITORY"]), version, commit)
    if args.command == "inspect":
        print("build=" + str(publisher.inspect()[0]).lower())
    else:
        if args.apk is None:
            parser.error("Укажите подписанный APK")
        with tempfile.TemporaryDirectory() as folder:
            publisher.publish(args.apk, Path(folder))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
