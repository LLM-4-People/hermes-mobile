import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from seed_account import seed_account
from accounts import (
    CADDY_IMAGE,
    profile_endpoint,
    provision,
    push_transports,
    single_host_routes,
    stack,
    validate_hostname,
    validate_profiles,
)

IMAGE = "sha256:" + "1" * 64


class AccountDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.host = validate_hostname("Hermes.Example.Org")
        self.profiles = validate_profiles(["alice", "bob_work"])

    def test_single_hostname_routes_isolated_profiles_under_path_prefix(self):
        config, routes = stack(self.host, self.profiles, {"bob_work": True}, IMAGE, CADDY_IMAGE, True, 0)
        services = config["services"]
        self.assertEqual("hermes.example.org", self.host)
        self.assertEqual("https://hermes.example.org/p/alice", profile_endpoint(self.host, "alice"))
        self.assertEqual("https://hermes.example.org/p/bob_work", profile_endpoint(self.host, "bob_work"))
        self.assertEqual(["127.0.0.1:0:443"], services["https"]["ports"])
        self.assertEqual(set(config["networks"]), set(services["https"]["networks"]))
        # Only one HTTPS site block for all profiles — never separate per-user hostnames.
        self.assertEqual(1, routes.count("hermes.example.org {"))
        self.assertIn("handle_path /p/alice/* {", routes)
        self.assertIn("handle_path /p/bob_work/* {", routes)
        self.assertIn("@nested_profiles path /p /p/*", routes)
        self.assertIn(
            "@push path /api/mobile/push /api/mobile/push/* /api/mobile/deliveries /api/mobile/deliveries/*",
            routes,
        )
        self.assertIn("reverse_proxy gateway-alice:8642", routes)
        self.assertIn("reverse_proxy gateway-bob_work:8642", routes)
        self.assertNotIn("push-alice", routes)
        self.assertIn("reverse_proxy push-bob_work:8766", routes)
        alice_block = routes.split("handle_path /p/alice/* {")[1].split("handle_path /p/bob_work/* {")[0]
        bob_block = routes.split("handle_path /p/bob_work/* {")[1]
        self.assertIn(
            "@push path /api/mobile/push /api/mobile/push/* /api/mobile/deliveries /api/mobile/deliveries/*\n"
            "        handle @push {\n"
            "            respond 404\n"
            "        }",
            alice_block,
        )
        self.assertIn(
            "@push path /api/mobile/push /api/mobile/push/* /api/mobile/deliveries /api/mobile/deliveries/*\n"
            "        handle @push {\n"
            "            reverse_proxy push-bob_work:8766\n"
            "        }",
            bob_block,
        )
        self.assertTrue(routes.rstrip().endswith("    handle {\n        respond 404\n    }\n}"))
        for name in self.profiles:
            gateway = services["gateway-" + name]
            self.assertNotIn("ports", gateway)
            self.assertNotIn("network_mode", gateway)
            self.assertEqual(["user-" + name], gateway["networks"])
            home, seed, nearby, plugin = gateway["volumes"]
            self.assertEqual("/opt/data/plugins/mobile_nearby", nearby["target"])
            self.assertTrue(nearby["read_only"] and (Path(nearby["source"]) / "plugin.yaml").is_file())
            self.assertNotIn("entrypoint", gateway)
            self.assertEqual("/etc/cont-init.d/00-mobile-account", seed["target"])
            self.assertTrue(seed["read_only"] and os.access(seed["source"], os.X_OK))
            self.assertEqual("home-" + name + ":/opt/data", home)
            self.assertEqual("/opt/data/plugins/mobile_artifacts", plugin["target"])
            self.assertTrue(plugin["read_only"])
            self.assertEqual(["gateway", "run"], gateway["command"])
            self.assertEqual("false", gateway["environment"]["GATEWAY_MULTIPLEX_PROFILES"])
            self.assertTrue(all("/" + name + "/" in item["path"] for item in gateway["env_file"]))
        self.assertNotIn("push-alice", services)
        bridge = services["push-bob_work"]
        self.assertEqual(["user-bob_work"], bridge["networks"])
        self.assertIn("home-bob_work:/opt/data:ro", bridge["volumes"])
        self.assertNotIn("home-alice", str(bridge))
        self.assertNotIn("provider.env", str(bridge))

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
            provision(root, self.profiles)
            first = root / "accounts" / "alice"
            second = root / "accounts" / "bob_work"
            (first / "push.env").write_text("MOBILE_UNIFIEDPUSH_ORIGIN=https://push.example.org\n")
            (second / "push.env").write_text("MOBILE_FIREBASE_PROJECT_ID=fixture\n")
            with self.assertRaisesRegex(ValueError, "firebase.json"):
                push_transports(root, self.profiles)
            for account in (first, second):
                (account / "firebase.json").write_text("{}")
            providers = push_transports(root, self.profiles)
            services = stack(self.host, self.profiles, providers, IMAGE, CADDY_IMAGE, True, 0)[0]["services"]
            self.assertNotIn("firebase.json", str(services["push-alice"]))
            self.assertIn("firebase.json", str(services["push-bob_work"]))

    def test_reject_invalid_hostname_profiles_or_mutable_images(self):
        for bad_host in ["", "localhost", "https://hermes.example.org", "hermes.example.org/p/a",
                         "hermes.example.org:443", "bad host.example.org", "one.example\nother.example",
                         "a..b.example.org"]:
            with self.subTest(bad_host=bad_host), self.assertRaises(ValueError):
                validate_hostname(bad_host)
        for bad_profiles in [[], ["alice", "alice"], ["../other"], ["Alice"], ["a/b"],
                             ["alice=alice.example.org"], ["-alice"], ["alice_"],
                             ["a" * 33], ["alice bob"], ["alice\nbob"], ["."]]:
            with self.subTest(bad_profiles=bad_profiles), self.assertRaises(ValueError):
                validate_profiles(bad_profiles)
        for image, pushes in [(IMAGE, {"missing": True}), ("hermes:latest", {})]:
            with self.assertRaises(ValueError):
                stack(self.host, self.profiles, pushes, image, CADDY_IMAGE, False, 443)
        with self.assertRaises(ValueError):
            single_host_routes("https://bad.example.org", ["alice"], {}, False)

    def test_cli_renders_single_hostname_stack_and_rejects_bad_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "deploy"
            command = [
                sys.executable, str(Path(__file__).with_name("accounts.py")), str(root),
                "--host", "hermes.example.org",
                "--profile", "alice", "--profile", "bob",
                "--image", IMAGE,
            ]
            completed = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertIn("https://hermes.example.org/p/alice", completed.stdout)
            self.assertIn("https://hermes.example.org/p/bob", completed.stdout)
            caddy = (root / "Caddyfile").read_text()
            self.assertEqual(1, caddy.count("hermes.example.org {"))
            self.assertIn("handle_path /p/alice/* {", caddy)
            self.assertIn("handle_path /p/bob/* {", caddy)
            compose = json.loads((root / "compose.json").read_text())
            self.assertIn("gateway-alice", compose["services"])
            self.assertIn("gateway-bob", compose["services"])
            self.assertEqual(["80:80", "443:443"], compose["services"]["https"]["ports"])

            bad = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("accounts.py")), str(root),
                 "--host", "hermes.example.org", "--profile", "../escape", "--image", IMAGE],
                capture_output=True, text=True,
            )
            self.assertNotEqual(0, bad.returncode)

    def test_same_basename_separate_projects_and_relocation(self):
        with tempfile.TemporaryDirectory() as directory:
            east = Path(directory) / "east" / "deployment"
            west = Path(directory) / "west" / "deployment"
            first = provision(east, self.profiles)
            second = provision(west, self.profiles)
            self.assertNotEqual(first, second)
            self.assertEqual(first, provision(east, self.profiles))
            moved = east.with_name("new-name")
            east.rename(moved)
            self.assertEqual(first, provision(moved, self.profiles))

    @unittest.skipUnless(shutil.which("docker"), "Docker Compose is required to inspect actual volume names")
    def test_rendered_compose_keeps_separate_volumes_for_equal_basenames(self):
        def volume_name(directory):
            command = [sys.executable, str(Path(__file__).with_name("accounts.py")), str(directory),
                       "--host", "hermes.example.org", "--profile", "one", "--image", IMAGE]
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
            provision(root, self.profiles)
            paths = [root / "accounts" / name / "gateway.env" for name in self.profiles]
            keys = [p.read_text() for p in paths]
            self.assertNotEqual(*keys)
            provision(root, self.profiles)
            self.assertEqual(keys, [p.read_text() for p in paths])
            paths[1].write_text(keys[0])
            with self.assertRaisesRegex(ValueError, "unique"):
                provision(root, self.profiles)
            os.chmod(paths[0], 0o644)
            with self.assertRaisesRegex(ValueError, "private"):
                provision(root, self.profiles)
