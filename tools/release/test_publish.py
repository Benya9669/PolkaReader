import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from publish import Publisher, TransientReadError, apk_name, candidate, complete, verify_apk, version_code


FIRST = "1" * 40
NEXT = "2" * 40


class LocalGitHub:
    """An in-memory release store with GitHub's create-only tag and unique asset names."""
    def __init__(self):
        self.rows = []
        self.tags = {}
        self.files = {}
        self.changes = []
        self.fail_upload = None

    def releases(self):
        return self.rows

    def release(self, release_id):
        return self.rows[release_id - 1]

    def release_by_tag(self, tag):
        return next((r for r in self.rows if not r["draft"] and r["tag_name"] == tag), None)

    def tag_commit(self, tag):
        return self.tags.get(tag)

    def api(self, path, method, body=None):
        self.changes.append((method, path))
        if (path, method) == ("releases", "POST"):
            release = {**body, "id": len(self.rows) + 1, "author": {"login": "github-actions[bot]"},
                       "prerelease": False, "assets": []}
            self.rows.append(release)
            return release
        if (path, method) == ("git/refs", "POST"):
            tag = body["ref"].removeprefix("refs/tags/")
            if tag in self.tags:
                raise ValueError("Existing ref")
            self.tags[tag] = body["sha"]
            return
        if path.startswith("releases/assets/") and method == "DELETE":
            asset_id = int(path.rsplit("/", 1)[-1])
            for row in self.rows:
                for asset in row["assets"]:
                    if asset["id"] == asset_id:
                        assert row["draft"] and asset["state"] == "starter"
                        row["assets"].remove(asset)
                        return
        if method == "PATCH":
            row = self.rows[int(path.split("/")[1]) - 1]
            assert row["draft"]
            row.update(body)
            return row
        raise AssertionError((path, method))

    def upload(self, release_id, name, path):
        row = self.rows[release_id - 1]
        assert row["draft"] and all(a["name"] != name for a in row["assets"])
        self.changes.append(("UPLOAD", name))
        asset_id = max(self.files, default=0) + 1
        if self.fail_upload == name:
            self.fail_upload = None
            row["assets"].append(dict(id=asset_id, name=name, state="starter", size=0))
            raise OSError("Upload interrupted")
        data = path.read_bytes()
        self.files[asset_id] = data
        row["assets"].append(dict(id=asset_id, name=name, state="uploaded", size=len(data),
                                  digest="sha256:" + hashlib.sha256(data).hexdigest()))

    def download(self, asset, path):
        path.write_bytes(self.files[asset["id"]])


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.file = self.folder / "local.apk"
        self.file.write_bytes(b"signing-key-A\nfirst build")
        self.service = LocalGitHub()
        self.pub = Publisher(self.service, "0.1.0", FIRST)
        # Only the APK toolchain is replaced. The publication state machine runs normally.
        mock = patch("publish.verify_apk", side_effect=lambda p, v: p.read_bytes().splitlines()[0].decode())
        mock.start()
        self.addCleanup(mock.stop)

    def test_first_release_and_repeat_do_not_replace_assets(self):
        self.pub.publish(self.file, self.folder)
        self.assertEqual(self.service.tags, {"v0.1.0": FIRST})
        row = self.service.rows[0]
        self.assertEqual(row["name"], "Polka Reader v0.1.0")
        self.assertTrue(row["generate_release_notes"])
        self.assertFalse(row["draft"])
        self.assertEqual(len(row["assets"]), 2)
        complete(row, "0.1.0")
        changes = list(self.service.changes)
        Publisher(self.service, "0.1.0", NEXT).publish(self.file, self.folder)
        self.assertEqual(self.service.changes, changes)

    def test_new_version_uses_its_own_tested_commit(self):
        self.pub.publish(self.file, self.folder)
        Publisher(self.service, "0.1.1", NEXT).publish(self.file, self.folder)
        self.assertEqual(self.service.tags, {"v0.1.0": FIRST, "v0.1.1": NEXT})
        self.assertEqual(len(self.service.rows), 2)

    def test_new_draft_and_uploads_can_lag_behind_the_write(self):
        seen = set()
        def delayed(release_id):
            row = self.service.rows[release_id - 1]
            state = (row["draft"], len(row["assets"]))
            if state not in seen:
                seen.add(state)
                if not row["assets"]:
                    raise TransientReadError("HTTP 404", 404)
                if not row["draft"]:
                    return {**row, "draft": True}
                return {**row, "assets": row["assets"][:-1]}
            return row

        with patch.object(self.service, "releases", return_value=[]), patch.object(
            self.service, "release", side_effect=delayed
        ), patch("publish.time.sleep") as slept:
            self.pub.publish(self.file, self.folder)
        self.assertGreaterEqual(slept.call_count, 4)
        self.assertFalse(self.service.rows[0]["draft"])
        complete(self.service.rows[0], "0.1.0")

    def test_reuse_own_untagged_draft_but_do_not_touch_foreign_draft(self):
        self.service.fail_upload = apk_name("0.1.0")
        with self.assertRaises(OSError):
            self.pub.publish(self.file, self.folder)
        row = self.service.rows[0]
        row["tag_name"] = "untagged-123abc"
        with self.assertRaisesRegex(ValueError, "Черновик"):
            Publisher(self.service, "0.1.0", NEXT).publish(self.file, self.folder)
        self.pub.publish(self.file, self.folder)
        self.assertEqual(len(self.service.rows), 1)
        self.assertFalse(row["draft"])
        self.assertEqual(row["tag_name"], self.pub.tag)

    def test_upload_interruption_resumes_the_original_apk(self):
        self.service.fail_upload = apk_name("0.1.0") + ".sha256"
        with self.assertRaises(OSError):
            self.pub.publish(self.file, self.folder)
        row = self.service.rows[0]
        self.assertTrue(row["draft"])
        self.assertFalse(self.service.tags)
        original = self.file.read_bytes()
        self.file.write_bytes(b"signing-key-A\ndifferent runner timestamp")
        self.pub.publish(self.file, self.folder)
        self.assertFalse(row["draft"])
        self.assertEqual(self.service.files[row["assets"][0]["id"]], original)
        self.assertEqual(self.service.changes.count(("UPLOAD", apk_name("0.1.0"))), 1)

    def test_foreign_tag_or_draft_fails_closed(self):
        self.service.tags[self.pub.tag] = NEXT
        with self.assertRaisesRegex(ValueError, "другой commit"):
            self.pub.publish(self.file, self.folder)
        self.assertFalse(self.service.changes)
        self.service.tags.clear()
        self.service.fail_upload = apk_name("0.1.0")
        with self.assertRaises(OSError):
            self.pub.publish(self.file, self.folder)
        with self.assertRaisesRegex(ValueError, "Черновик"):
            Publisher(self.service, "0.1.0", NEXT).publish(self.file, self.folder)
        self.service.rows[0]["author"]["login"] = "other"
        with self.assertRaisesRegex(ValueError, "Черновик"):
            self.pub.publish(self.file, self.folder)

    def test_tag_created_by_another_writer_is_never_moved(self):
        api = self.service.api

        def race(path, method, body=None):
            if path == "git/refs" and method == "POST":
                self.service.tags[self.pub.tag] = NEXT
            return api(path, method, body)

        with patch.object(self.service, "api", side_effect=race), self.assertRaisesRegex(ValueError, "Existing ref"):
            self.pub.publish(self.file, self.folder)
        self.assertEqual(self.service.tags[self.pub.tag], NEXT)
        self.assertTrue(self.service.rows[0]["draft"])
        with self.assertRaisesRegex(ValueError, "другой commit"):
            self.pub.publish(self.file, self.folder)

    def test_incomplete_published_release_is_preserved(self):
        self.pub.publish(self.file, self.folder)
        self.service.rows[0]["assets"].pop()
        changes = list(self.service.changes)
        with self.assertRaisesRegex(ValueError, "отсутствует"):
            self.pub.publish(self.file, self.folder)
        self.assertEqual(changes, self.service.changes)

    def test_invalid_published_checksum_is_rejected(self):
        self.pub.publish(self.file, self.folder)
        self.service.rows[0]["assets"][1]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ValueError, "сумма"):
            self.pub.inspect()

    def test_upgrade_rejects_key_replacement_and_downgrade(self):
        self.pub.publish(self.file, self.folder)
        self.file.write_bytes(b"signing-key-B\nnew build")
        with self.assertRaisesRegex(ValueError, "Сертификат"):
            Publisher(self.service, "0.2.0", NEXT).publish(self.file, self.folder)
        with self.assertRaisesRegex(ValueError, "versionCode"):
            Publisher(self.service, "0.0.1", NEXT).publish(self.file, self.folder)
        self.assertEqual(len(self.service.rows), 1)

    def test_only_version_changes_and_main_dispatch_are_candidates(self):
        main = "refs/heads/main"
        self.assertFalse(candidate("pull_request", main, None, "0.1.0"))
        self.assertFalse(candidate("workflow_dispatch", "refs/heads/topic", None, "0.1.0"))
        self.assertTrue(candidate("workflow_dispatch", main, None, "0.1.0"))
        self.assertTrue(candidate("push", main, "0" * 40, "0.1.0"))
        for new_version, expected in [("0.1.0", False), ("0.1.1", True)]:
            with patch("publish.subprocess.check_output", side_effect=[b"VERSION", b"0.1.0\n"]):
                self.assertEqual(candidate("push", main, FIRST, new_version), expected)

    def test_version_code_boundaries(self):
        self.assertEqual(version_code("2099.999.999"), 2_099_999_999)
        for invalid in ["01.0.0", "0.0.0", "2100.0.0", "1.1000.0", "1.0.1000", "v0.1.0"]:
            with self.assertRaises(ValueError):
                version_code(invalid)


class ApkMetadataTest(unittest.TestCase):
    def test_signed_manifest_must_match_exact_package_and_version(self):
        certificates = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        correct = "package: name='ru.polka.reader' versionCode='1000' versionName='0.1.0'"
        for metadata in [correct, correct.replace("1000", "2"), correct.replace("ru.polka.reader", "other"),
                         correct.replace("0.1.0", "0.2.0"), correct + " split='x86'"]:
            with patch.dict("os.environ", {"ANDROID_HOME": "/sdk"}), patch(
                "publish.subprocess.check_output", side_effect=[certificates, metadata]
            ):
                if metadata == correct:
                    self.assertEqual(verify_apk(Path("sample.apk"), "0.1.0"), "a" * 64)
                else:
                    with self.assertRaises(ValueError):
                        verify_apk(Path("sample.apk"), "0.1.0")
