"""Drive the test-APK-only conversation instrumentation on a designated emulator."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import hashlib
import json
import secrets

from .installed import installed_apk_sha256
from .run import APP, Emulator, android_png_size, cleanup_all


DRIVER = f"{APP}.test/{APP}.journey.ConversationCardsJourney"
CAPTURES = {"card-voice-draft.png", "card-restored-history.png", *(f"card-{n}.png" for n in range(1, 7))}


class CardPhone:
    def __init__(self, args, gateway, turns):
        self.device = Emulator(args.serial, args.adb, args.adb_port)
        self.gateway = gateway
        self.turns = turns
        self.output = args.output
        self.verify_apks = args.verify_apks
        self.tag = secrets.token_hex(8)
        self.directory = "cache/cards-" + self.tag
        self.stack = ExitStack()

    def __enter__(self):
        device = self.device
        if device.shell("getprop", "ro.kernel.qemu") != "1":
            raise ValueError("This journey creates an account on a dedicated emulator")
        if DRIVER not in device.shell("pm", "list", "instrumentation"):
            raise RuntimeError("Install the conversation milestone's debug APK and androidTest APK")
        self.apk_hash = installed_apk_sha256(device, APP)
        self.test_apk_hash = installed_apk_sha256(device, APP + ".test")
        if self.verify_apks:
            for path, installed in zip(self.verify_apks, (self.apk_hash, self.test_apk_hash), strict=True):
                with path.open("rb") as apk:
                    if hashlib.file_digest(apk, "sha256").hexdigest() != installed:
                        raise ValueError(f"Installed APK differs from {path.name}")
        try:
            endpoint = self.stack.enter_context(device.reverse(self.gateway.endpoint))
            self.stack.enter_context(device.appearance())
            device.shell("run-as", APP, "mkdir", self.directory)
            self.stack.callback(self.cleanup)
            spec = {"endpoint": endpoint, "key": self.gateway.key, "phrase": secrets.token_urlsafe(24),
                    "profile": "Notes " + self.tag, "turns": self.turns}
            device.run("shell", "run-as", APP, "sh", "-c",
                       f"'umask 077 && cat > {self.directory}/fixture.json'",
                       data=json.dumps(spec, ensure_ascii=False).encode())
            self.pool = self.stack.enter_context(ThreadPoolExecutor(max_workers=1))
            self.task = self.pool.submit(device.run, "shell", "am", "instrument", "-w", "-r",
                                         "-e", "tag", self.tag, DRIVER, timeout=720)
            return self
        except BaseException:
            self.stack.close()
            raise

    def __exit__(self, kind, error, traceback):
        def abort():
            if error and not self.task.done():
                self.device.shell("run-as", APP, "touch", self.directory + "/abort")
        cleanup_all(abort, lambda: self.stack.__exit__(kind, error, traceback))
        # The executor has now joined, even if Android was still cleaning up its profile
        # when the host timed out. Keep its more specific assertion with the host failure.
        if error is not None:
            try:
                self.check_instrumentation()
            except Exception as native_error:
                if str(native_error) != str(error):
                    error.add_note(f"Android instrumentation: {native_error}")

    def check(self):
        if self.task.done():
            self.finish()

    def permit(self, index):
        self.device.shell("run-as", APP, "touch", self.directory + f"/permit-{index}")

    def voice_filled(self):
        return "voice-filled" in self.device.shell("run-as", APP, "ls", "-1", self.directory).splitlines()

    def submit_voice(self):
        self.device.shell("run-as", APP, "touch", self.directory + "/permit-voice-submit")

    def check_instrumentation(self):
        status = self.task.result().decode(errors="replace")
        if "cards=passed" not in status or "INSTRUMENTATION_CODE: -1" not in status:
            raise AssertionError(status)

    def finish(self):
        self.check_instrumentation()
        files = self.device.shell("run-as", APP, "ls", "-1", self.directory).splitlines()
        missing = CAPTURES.difference(files)
        if missing:
            raise AssertionError("Missing Android captures: " + ", ".join(sorted(missing)))

    def evidence(self):
        captures = {}
        for name in sorted(CAPTURES):
            image = (self.output / name).read_bytes()
            android_png_size(image, name)
            captures[name] = hashlib.sha256(image).hexdigest()
        return {
            "apk_sha256": self.apk_hash,
            "test_apk_sha256": self.test_apk_hash,
            "captures_sha256": captures,
        }

    def cleanup(self):
        device = self.device
        def collect():
            names = device.shell("run-as", APP, "ls", "-1", self.directory).splitlines()
            for name in names:
                if name in CAPTURES:
                    image = device.run("exec-out", "run-as", APP, "cat", self.directory + "/" + name)
                    android_png_size(image, name)
                    (self.output / name).write_bytes(image)
        cleanup_all(collect, lambda: device.shell("run-as", APP, "rm", "-r", self.directory))
