"""Shared Android emulator viewport override, settlement, and restoration helpers."""

from contextlib import contextmanager
import re
import time

try:
    from .run import cleanup_all
except ImportError:
    from run import cleanup_all

CONFIG_SIZE = re.compile(r"-w\d+dp-h\d+dp-")
CONFIG_ORIENTATION = re.compile(r"-(?:port|land)-")


def await_viewport(device, config_tokens, expected_rotation, description, timeout=20.0):
    """Wait until Android reports both the requested configuration tokens and wm user-rotation state."""
    deadline = time.monotonic() + timeout
    last_config = ""
    last_rotation = ""
    while True:
        last_config = device.shell("am", "get-config")
        last_rotation = device.shell("wm", "user-rotation")
        if all(token in last_config for token in config_tokens) and last_rotation == expected_rotation:
            return
        if time.monotonic() >= deadline:
            break
        time.sleep(0.05)
    raise AssertionError(
        f"Emulator did not settle to {description} "
        f"(expected tokens={list(config_tokens)}, rotation={expected_rotation!r}; "
        f"observed config={last_config!r}, rotation={last_rotation!r})"
    )


def set_viewport(device, width, height, *, density=160, rotation="1", timeout=20.0, description=None):
    """Apply size, density, and locked rotation, then wait for Android config settlement."""
    device.shell("wm", "size", f"{min(width, height)}x{max(width, height)}")
    device.shell("wm", "density", str(density))
    device.shell("settings", "put", "system", "user_rotation", rotation)
    device.shell("wm", "user-rotation", "lock", rotation)
    await_viewport(
        device,
        (f"-w{width}dp-h{height}dp-",),
        f"lock {rotation}",
        description or f"{width}x{height}@{density} viewport",
        timeout=timeout,
    )


def _restore_tokens(initial_config):
    tokens = []
    size_match = CONFIG_SIZE.search(initial_config)
    if size_match:
        tokens.append(size_match.group(0))
    orient_match = CONFIG_ORIENTATION.search(initial_config)
    if orient_match:
        tokens.append(orient_match.group(0))
    return tuple(tokens)


@contextmanager
def restore_display(device, timeout=20.0):
    """Preserve emulator display size, density, and rotation across viewport overrides."""
    overrides = {}
    for setting in ("size", "density"):
        old = device.shell("wm", setting)
        match = re.search(r"^Override (?:size|density): ([\dx]+)$", old, re.MULTILINE)
        overrides[setting] = match.group(1) if match else "reset"
    rotation = device.shell("wm", "user-rotation").split()
    saved_rotation = device.shell("settings", "get", "system", "user_rotation")
    if not re.fullmatch(r"free|lock [0-3]", " ".join(rotation)):
        raise ValueError("Unrecognized current Android display rotation")
    initial_config = device.shell("am", "get-config")
    restore_tokens = _restore_tokens(initial_config)
    if len(rotation) == 2:
        restore_rot = rotation[1]
    elif saved_rotation in {"0", "1", "2", "3"}:
        restore_rot = saved_rotation
    else:
        restore_rot = "1" if "-land-" in initial_config else "0"
    try:
        yield
    finally:
        # On API 36, releasing user-rotation to "free" while still in landscape leaves the
        # display stuck rotated; drive back to the pre-test orientation and await settlement
        # before restoring the original rotation mode.
        def drive_and_settle():
            device.shell("wm", "user-rotation", "lock", restore_rot)
            if restore_tokens:
                await_viewport(
                    device,
                    restore_tokens,
                    f"lock {restore_rot}",
                    f"pre-test display orientation (lock {restore_rot})",
                    timeout=timeout,
                )

        def restore_mode():
            device.setting("user_rotation", saved_rotation)
            device.shell("wm", "user-rotation", *rotation)
            if restore_tokens:
                await_viewport(
                    device,
                    restore_tokens,
                    " ".join(rotation),
                    f"restored display mode ({' '.join(rotation)})",
                    timeout=timeout,
                )

        cleanup_all(
            lambda: device.shell("wm", "size", overrides["size"]),
            lambda: device.shell("wm", "density", overrides["density"]),
            drive_and_settle,
            restore_mode,
        )
