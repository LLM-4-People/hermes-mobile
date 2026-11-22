"""Drive the test-APK-only conversation instrumentation on a designated emulator."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import json
import re
import secrets

from .run import APP, Emulator, cleanup_all


DRIVER = f"{APP}.test/{APP}.journey.ConversationCardsJourney"


class CardPhone:
    def __init__(self, args, gateway, turns):
        self.device = Emulator(args.serial, args.adb, args.adb_port)
        self.gateway = gateway
        self.turns = turns
        self.output = args.output
        self.tag = secrets.token_hex(8)
        self.directory = "cache/cards-" + self.tag
        self.stack = ExitStack()

    def __enter__(self):
        device = self.device
        if device.shell("getprop", "ro.kernel.qemu") != "1":
            raise ValueError("This journey creates an account on a dedicated emulator")
        if DRIVER not in device.shell("pm", "list", "instrumentation"):
            raise RuntimeError("Install the conversation milestone's debug APK and androidTest APK")
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

    def check(self):
        if self.task.done():
            self.finish()

    def permit(self, index):
        self.device.shell("run-as", APP, "touch", self.directory + f"/permit-{index}")

    def voice_filled(self):
        return "voice-filled" in self.device.shell("run-as", APP, "ls", "-1", self.directory).splitlines()

    def submit_voice(self):
        self.device.shell("run-as", APP, "touch", self.directory + "/permit-voice-submit")

    def finish(self):
        status = self.task.result().decode(errors="replace")
        if "cards=passed" not in status or "INSTRUMENTATION_CODE: -1" not in status:
            raise AssertionError(status)

    def cleanup(self):
        device = self.device
        def collect():
            names = device.shell("run-as", APP, "ls", "-1", self.directory).splitlines()
            for name in names:
                if re.fullmatch(r"card-[1-6]\.png", name):
                    image = device.run("exec-out", "run-as", APP, "cat", self.directory + "/" + name)
                    if not image.startswith(b"\x89PNG\r\n\x1a\n"):
                        raise AssertionError("Invalid capture " + name)
                    (self.output / name).write_bytes(image)
        cleanup_all(collect, lambda: device.shell("run-as", APP, "rm", "-r", self.directory))
