#!/opt/hermes/.venv/bin/python -I
"""Seed a new mobile home before the image's normal s6 account setup."""
import os
from pathlib import Path
import tempfile


def seed_account(home, template):
    target = home / "config.yaml"
    if os.path.lexists(target):
        return
    # The immutable gateway image supplies its YAML parser and complete config example.
    from ruamel.yaml import YAML

    yaml = YAML()
    yaml.preserve_quotes = True
    with template.open(encoding="utf-8") as stream:
        config = yaml.load(stream)
    toolsets = config.setdefault("platform_toolsets", {})
    if "api_server" in toolsets:
        # A customized image already defines the policy. The image will seed its own example.
        return
    toolsets["api_server"] = ["hermes-api-server", "clarify"]
    plugins = config.setdefault("plugins", {})
    enabled = plugins.setdefault("enabled", [])
    if "mobile_nearby" not in enabled and "mobile_nearby" not in plugins.get("disabled", []):
        enabled.append("mobile_nearby")

    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=home) as stream:
        yaml.dump(config, stream)
        stream.flush()
        try:
            os.link(stream.name, target)
        except FileExistsError:
            # Another gateway or an operator completed the config first.
            pass


if __name__ == "__main__":
    seed_account(Path("/opt/data"), Path("/opt/hermes/cli-config.yaml.example"))
