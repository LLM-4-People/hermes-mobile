"""Build an offline French walkthrough from a completed thread-instrument phone receipt."""

import argparse
import base64
import hashlib
from html import escape
import json
import os
from pathlib import Path
import re
import stat
import tempfile

from device.run import android_png_size


# A numbered journey stage can have additional verified views. Its first declared capture is the overview.
STEP_NAMES = (
    "Demander un outil", "Essayer le formulaire", "Préparer le brouillon", "Comparer la révision",
    "Adopter la version", "Annuler la révision", "Ouvrir les deux panneaux",
)
TEMPLATE = Path(__file__).with_name("instrument_walkthrough.html")


def read_evidence(path, limit):
    if path.is_symlink():
        raise ValueError(f"Symlink in phone evidence: {path.name}")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    with os.fdopen(descriptor, "rb") as handle:
        meta = os.fstat(handle.fileno())
        if not stat.S_ISREG(meta.st_mode) or not 0 < meta.st_size <= limit:
            raise ValueError(f"Invalid evidence file: {path.name}")
        data = handle.read(limit + 1)
    if len(data) != meta.st_size:
        raise ValueError(f"Evidence changed during export: {path.name}")
    return data


def render(directory, expected, report):
    records = report.get("captures")
    if not isinstance(records, dict) or records.keys() != expected.keys():
        raise ValueError("Capture names differ from the phone journey")
    if not (re.fullmatch(r"[0-9a-f]{40}", str(report.get("gateway_revision", "")))
            and all(re.fullmatch(r"[0-9a-f]{64}", str(report.get(key, ""))) for key in ("apk_sha256", "test_apk_sha256"))
            and report.get("automatically_submitted_turns") == 0
            and len(report.get("canonical_receipts", [])) == 4
            and all(row.get("status") == "completed" for row in report["canonical_receipts"])):
        raise ValueError("A completed phone receipt is required")
    stages = {}
    for name, caption in expected.items():
        stage, _, _ = name.partition("-")
        entry = records[name]
        if not re.fullmatch(r"[0-9]{2}-[a-z-]+\.png", name) or not isinstance(entry, dict) or entry.get("caption") != caption:
            raise ValueError("Unexpected phone capture name or caption")
        data = read_evidence(directory / name, 8 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != entry.get("sha256"):
            raise ValueError(f"Capture SHA-256 differs from the receipt: {name}")
        width, height = android_png_size(data, name)
        if (width, height) != (entry.get("width"), entry.get("height")):
            raise ValueError(f"Capture dimensions differ from the receipt: {name}")
        if stage not in stages:
            stages[stage] = (caption, width, height, data)

    navigation, figures = [], []
    for number, title in enumerate(STEP_NAMES, 1):
        stage = str(18 + number)
        if stage not in stages:
            raise ValueError(f"Missing phone journey stage {stage}")
        caption, width, height, data = stages[stage]
        title = escape(title)
        navigation.append(f'<li><a href="#etape-{number}">{title}</a></li>')
        source = base64.b64encode(data).decode("ascii")
        figures.append(f'<figure id="etape-{number}"><div class="canvas"><img width="{width}" height="{height}" '
                       f'alt="Capture Android · {title}" src="data:image/png;base64,{source}"></div>'
                       f'<figcaption>{escape(caption)}</figcaption></figure>')
    template = TEMPLATE.read_text(encoding="utf-8")
    script = template.split("<script>", 1)[1].split("</script>", 1)[0].encode()
    digest = base64.b64encode(hashlib.sha256(script).digest()).decode("ascii")
    return template.replace("SCRIPT_DIGEST", digest).replace("<!-- STEPS -->", "\n".join(navigation)).replace(
        "<!-- SCREENS -->", "\n".join(figures))


def write_walkthrough(directory, expected, report=None):
    if report is None:
        report = json.loads(read_evidence(directory / "thread-instrument.json", 1024 * 1024))
    page = render(directory, expected, report)
    with tempfile.NamedTemporaryFile("w", dir=directory, encoding="utf-8", delete=False) as temporary:
        try:
            temporary.write(page)
            temporary.close()
            os.replace(temporary.name, directory / "walkthrough.html")
        finally:
            Path(temporary.name).unlink(missing_ok=True)


if __name__ == "__main__":
    from thread_instruments import CAPTURES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Directory containing the phone receipt and original PNGs")
    write_walkthrough(parser.parse_args().directory, CAPTURES)
