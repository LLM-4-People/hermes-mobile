"""The inbox can show the target title and reply preview without opening that thread."""
import unittest
from xml.etree.ElementTree import Element, SubElement

from verification.device.app_strings import AppStrings
from .ui import Screen, thread_screen


def snapshot(*labels):
    root = Element("hierarchy")
    for label in labels:
        SubElement(root, "node", {"text": label})
    return root


class ThreadRouteTest(unittest.TestCase):
    def test_requires_transcript_composer_and_both_unique_values(self):
        title, reply = "Discussion-3a0d", "Réponse du fil 3a0d"
        for lang in ("en", "fr"):
            with self.subTest(lang=lang):
                s = AppStrings.for_lang(lang)
                send = s["thread_send"]
                new_conv = s["thread_new_conversation"]
                ui = Screen(None, None, lang=lang)
                self.assertFalse(thread_screen(ui, snapshot(title, reply, new_conv), title, reply))
                self.assertFalse(thread_screen(ui, snapshot("Discussion-bd21", reply, send), title, reply))
                self.assertFalse(thread_screen(ui, snapshot(title, "Autre réponse", send), title, reply))
                self.assertTrue(thread_screen(ui, snapshot(title, reply, send), title, reply))
