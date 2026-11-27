"""Deterministic tests for Android XML AppStrings lookup and device locale detection."""

import unittest
from unittest.mock import Mock

from verification.device.app_strings import (
    APP,
    AppStrings,
    detect_device_lang,
    parse_locale_tags,
    resolve_lang,
)


class AppStringsTest(unittest.TestCase):
    def test_loads_exact_french_and_english_strings_from_android_xml(self):
        en = AppStrings.for_lang("en")
        fr = AppStrings.for_lang("fr")

        self.assertEqual(en.lang, "en")
        self.assertEqual(fr.lang, "fr")
        self.assertEqual(en["profile_choose_title"], "Choose a profile")
        self.assertEqual(fr["profile_choose_title"], "Choisir un profil")
        self.assertEqual(en["profile_unlock_action"], "Unlock")
        self.assertEqual(fr["profile_unlock_action"], "Déverrouiller")
        self.assertEqual(en["thread_send"], "Send")
        self.assertEqual(fr["thread_send"], "Envoyer")
        self.assertEqual(en["thread_message_hint"], "Message Hermes")
        self.assertEqual(fr["thread_message_hint"], "Message à Hermes")
        self.assertEqual(en["instrument_create_request"],
                         "Create a tool I can reuse in this discussion based on our conversation.")
        self.assertEqual(fr["instrument_create_request"],
                         "Crée un outil que je pourrai réutiliser dans cette discussion à partir de notre échange.")
        self.assertEqual(en["top_search"], "Search")
        self.assertEqual(fr["top_search"], "Rechercher")
        self.assertEqual(en["shortcut_atelier_short"], "Apps")
        self.assertEqual(fr["shortcut_atelier_short"], "Apps")
        self.assertEqual(en["shortcut_activity_short"], "Notes & tasks")
        self.assertEqual(fr["shortcut_activity_short"], "Notes et tâches")
        self.assertEqual(en["nav_settings"], "Settings")
        self.assertEqual(fr["nav_settings"], "Réglages")
        self.assertEqual(en["nav_notes_tasks"], "Notes & tasks")
        self.assertEqual(fr["nav_notes_tasks"], "Notes et tâches")
        self.assertEqual(en["nav_apps"], "Apps")
        self.assertEqual(fr["nav_apps"], "Apps")
        self.assertEqual(en["security_lock_now"], "Lock profile")
        self.assertEqual(fr["security_lock_now"], "Verrouiller le profil")

    def test_missing_key_raises_without_fallback_and_returns_fallback_when_supplied(self):
        en = AppStrings.for_lang("en")
        with self.assertRaises(KeyError):
            _ = en["nonexistent_test_key_xyz"]
        with self.assertRaises(KeyError):
            en.get("nonexistent_test_key_xyz")
        self.assertEqual(en.get("nonexistent_test_key_xyz", "Fallback Label"), "Fallback Label")
        self.assertEqual(en.get("thread_send", "Fallback Label"), "Send")

    def test_fmt_handles_positional_and_numbered_android_placeholders(self):
        en = AppStrings.for_lang("en")
        fr = AppStrings.for_lang("fr")
        self.assertIn("revue", en.fmt("instrument_revision_draft", "revue"))
        self.assertIn("revue", fr.fmt("instrument_revision_draft", "revue"))

    def test_plural_handles_en_and_fr_quantity_rules(self):
        en = AppStrings.for_lang("en")
        fr = AppStrings.for_lang("fr")
        self.assertEqual(en.plural("places_guide_count", 0, 0), "0 places")
        self.assertEqual(en.plural("places_guide_count", 1, 1), "1 place")
        self.assertEqual(en.plural("places_guide_count", 3, 3), "3 places")
        self.assertEqual(fr.plural("places_guide_count", 0, 0), "0 adresse")
        self.assertEqual(fr.plural("places_guide_count", 1, 1), "1 adresse")
        self.assertEqual(fr.plural("places_guide_count", 3, 3), "3 adresses")
        self.assertEqual(en.fmt("places_guide_count_of", en.plural("places_guide_count", 1, 1), 3), "1 place of 3")
        self.assertEqual(fr.fmt("places_guide_count_of", fr.plural("places_guide_count", 1, 1), 3), "1 adresse sur 3")

    def test_parse_locale_tags_and_resolve_lang(self):
        self.assertEqual(parse_locale_tags("Locales for org.llm4people.hermes for user 0 are []"), [])
        self.assertEqual(parse_locale_tags("Locales for org.llm4people.hermes for user 0 are [fr-CA, en-US]"),
                         ["fr-CA", "en-US"])
        self.assertEqual(resolve_lang(["en-US"]), "en")
        self.assertEqual(resolve_lang(["en_GB"]), "en")
        self.assertEqual(resolve_lang(["fr-CA", "en-US"]), "fr")
        self.assertEqual(resolve_lang(["de-DE", "en-US"]), "en")
        self.assertEqual(resolve_lang(["de-DE"]), "fr")
        self.assertEqual(resolve_lang([]), "en")

    def test_detect_device_lang_respects_per_app_override_and_system_locale_without_french_bias(self):
        def fake_device(responses):
            dev = Mock()
            dev.shell.side_effect = lambda *args: responses.get(args, "")
            return dev

        # Default CI emulator: empty per-app override, en-US system locale -> en
        en_dev = fake_device({
            ("cmd", "locale", "get-app-locales", APP): "Locales for org.llm4people.hermes for user 0 are []",
            ("getprop", "persist.sys.locale"): "en-US",
        })
        self.assertEqual(detect_device_lang(en_dev), "en")

        # French emulator: empty per-app override, fr-CA system locale -> fr
        fr_dev = fake_device({
            ("cmd", "locale", "get-app-locales", APP): "Locales for org.llm4people.hermes for user 0 are []",
            ("getprop", "persist.sys.locale"): "",
            ("getprop", "ro.product.locale"): "fr-CA",
        })
        self.assertEqual(detect_device_lang(fr_dev), "fr")

        # Per-app override beats system locale
        override_dev = fake_device({
            ("cmd", "locale", "get-app-locales", APP): "Locales for org.llm4people.hermes for user 0 are [fr-FR]",
            ("getprop", "persist.sys.locale"): "en-US",
        })
        self.assertEqual(detect_device_lang(override_dev), "fr")

        # None or silent device defaults cleanly to en without crashing
        self.assertEqual(detect_device_lang(None), "en")
        self.assertEqual(detect_device_lang(fake_device({})), "en")

    def test_thread_instruments_matches_localized_create_request_in_en_and_fr(self):
        import importlib
        import sys
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import patch

        verif_dir = str(Path(__file__).resolve().parents[1])
        fake_provider = SimpleNamespace(Text=lambda content: content)
        fake_fakes = SimpleNamespace(fake_llm_provider=fake_provider)
        fake_tests = SimpleNamespace(fakes=fake_fakes)

        with (
            patch.object(sys, "path", [verif_dir, *sys.path]),
            patch.dict(sys.modules, {
                "tests": fake_tests,
                "tests.fakes": fake_fakes,
                "tests.fakes.fake_llm_provider": fake_provider,
            }),
        ):
            thread_instruments = importlib.import_module("thread_instruments")
            en_create = AppStrings.for_lang("en")["instrument_create_request"]
            fr_create = AppStrings.for_lang("fr")["instrument_create_request"]
            self.assertNotEqual(en_create, fr_create)

            for lang, create, other_create in (("en", en_create, fr_create), ("fr", fr_create, en_create)):
                with self.subTest(lang=lang):
                    expected_prompt = create + "\n\n" + thread_instruments.GOAL
                    matched = thread_instruments.response(
                        {"body": {"messages": [{"role": "user", "content": expected_prompt}]}},
                        create_request=create,
                    )
                    self.assertEqual(matched, thread_instruments.proposal(False))

                    mismatched = thread_instruments.response(
                        {"body": {"messages": [{"role": "user", "content": other_create + "\n\n" + thread_instruments.GOAL}]}},
                        create_request=create,
                    )
                    self.assertEqual(mismatched, "Aucune proposition demandée.")


if __name__ == "__main__":
    unittest.main()
