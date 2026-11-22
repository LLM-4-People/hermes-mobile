import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from seed_account import seed_account
from accounts import CADDY_IMAGE, account_map, provision, push_transports, stack

IMAGE = "sha256:" + "1" * 64


class AccountDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.accounts = account_map(["one=one.example.org", "two=two.example.org"])

    def test_one_private_network_and_home_per_person(self):
        config, routes = stack(self.accounts, {"two": True}, IMAGE, CADDY_IMAGE, True, 0)
        services = config["services"]
        self.assertEqual(["127.0.0.1:0:443"], services["https"]["ports"])
        self.assertEqual(set(config["networks"]), set(services["https"]["networks"]))
        for name in self.accounts:
            gateway = services["gateway-" + name]
            self.assertNotIn("ports", gateway)
            self.assertNotIn("network_mode", gateway)
            self.assertEqual(["user-" + name], gateway["networks"])
            home, seed, plugin = gateway["volumes"]
            self.assertNotIn("entrypoint", gateway)
            self.assertEqual("/etc/cont-init.d/00-mobile-account", seed["target"])
            self.assertTrue(seed["read_only"] and os.access(seed["source"], os.X_OK))
            self.assertEqual("home-" + name + ":/opt/data", home)
            self.assertEqual("/opt/data/plugins/mobile_artifacts", plugin["target"])
            self.assertTrue(plugin["read_only"])
            self.assertEqual(["gateway", "run"], gateway["command"])
            self.assertEqual("false", gateway["environment"]["GATEWAY_MULTIPLEX_PROFILES"])
            self.assertTrue(all("/" + name + "/" in item["path"] for item in gateway["env_file"]))
        self.assertNotIn("push-one", services)
        bridge = services["push-two"]
        self.assertEqual(["user-two"], bridge["networks"])
        self.assertIn("home-two:/opt/data:ro", bridge["volumes"])
        self.assertNotIn("home-one", str(bridge))
        self.assertNotIn("provider.env", str(bridge))
        self.assertNotIn("push-one", routes)
        self.assertIn("reverse_proxy push-two:", routes)

    def test_existing_account_config_is_never_rewritten(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            target = home / "config.yaml"
            target.write_text("platform_toolsets:\n  api_server: []\n# owner policy\n")
            original = target.read_bytes()
            seed_account(home, home / "absent-template")
            self.assertEqual(original, target.read_bytes())
            target.unlink()
            target.symlink_to("operator-managed-config")
            seed_account(home, home / "absent-template")
            self.assertTrue(target.is_symlink())
            self.assertFalse((home / "operator-managed-config").exists())

    def test_credentials_follow_the_selected_push_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            provision(root, self.accounts)
            first = root / "accounts" / "one"
            second = root / "accounts" / "two"
            (first / "push.env").write_text("MOBILE_UNIFIEDPUSH_ORIGIN=https://push.example.org\n")
            (second / "push.env").write_text("MOBILE_FIREBASE_PROJECT_ID=fixture\n")
            with self.assertRaisesRegex(ValueError, "firebase.json"):
                push_transports(root, self.accounts)
            for account in (first, second):
                (account / "firebase.json").write_text("{}")
            providers = push_transports(root, self.accounts)
            services = stack(self.accounts, providers, IMAGE, CADDY_IMAGE, True, 0)[0]["services"]
            self.assertNotIn("firebase.json", str(services["push-one"]))
            self.assertIn("firebase.json", str(services["push-two"]))

    def test_reject_ambiguous_routing_or_mutable_images(self):
        for invalid in [["x=one.example", "x=two.example"], ["x=one.example", "y=ONE.example"],
                        ["x=y.example\nother"], ["../x=one.example"], ["x=https://one.example"]]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                account_map(invalid)
        for image, pushes in [(IMAGE, {"missing": True}), ("hermes:latest", {})]:
            with self.assertRaises(ValueError):
                stack(self.accounts, pushes, image, CADDY_IMAGE, False, 443)

    def test_same_basename_separate_projects_and_relocation(self):
        with tempfile.TemporaryDirectory() as directory:
            east = Path(directory) / "east" / "deployment"
            west = Path(directory) / "west" / "deployment"
            first = provision(east, self.accounts)
            second = provision(west, self.accounts)
            self.assertNotEqual(first, second)
            self.assertEqual(first, provision(east, self.accounts))
            moved = east.with_name("new-name")
            east.rename(moved)
            self.assertEqual(first, provision(moved, self.accounts))

    @unittest.skipUnless(shutil.which("docker"), "Docker Compose is required to inspect actual volume names")
    def test_rendered_compose_keeps_separate_volumes_for_equal_basenames(self):
        def volume_name(directory):
            command = [sys.executable, str(Path(__file__).with_name("accounts.py")), str(directory),
                       "--account", "one=one.example.org", "--image", IMAGE]
            subprocess.run(command, check=True, capture_output=True)
            spec = subprocess.check_output(["docker", "compose", "-f", str(directory / "compose.json"),
                                            "config", "--format", "json"])
            return json.loads(spec)["volumes"]["home-one"]["name"]

        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "east" / "deployment"
            second = Path(directory) / "west" / "deployment"
            volume = volume_name(first)
            self.assertNotEqual(volume, volume_name(second))
            self.assertEqual(volume, volume_name(first))

    def test_rerender_preserves_credentials_and_detects_bad_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            provision(root, self.accounts)
            paths = [root / "accounts" / name / "gateway.env" for name in self.accounts]
            keys = [p.read_text() for p in paths]
            self.assertNotEqual(*keys)
            provision(root, self.accounts)
            self.assertEqual(keys, [p.read_text() for p in paths])
            paths[1].write_text(keys[0])
            with self.assertRaisesRegex(ValueError, "unique"):
                provision(root, self.accounts)
            os.chmod(paths[0], 0o644)
            with self.assertRaisesRegex(ValueError, "private"):
                provision(root, self.accounts)
