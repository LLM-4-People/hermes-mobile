"""The inbox can show the target title and reply preview without opening that thread."""
import unittest
from xml.etree.ElementTree import Element, SubElement

from .ui import Screen, thread_screen


def snapshot(*labels):
    root = Element("hierarchy")
    for label in labels:
        SubElement(root, "node", {"text": label})
    return root


class ThreadRouteTest(unittest.TestCase):
    def test_requires_transcript_composer_and_both_unique_values(self):
        ui = Screen(None, None)
        title, reply = "Discussion-3a0d", "Réponse du fil 3a0d"
        self.assertFalse(thread_screen(ui, snapshot(title, reply, "Nouvelle conversation"), title, reply))
        self.assertFalse(thread_screen(ui, snapshot("Discussion-bd21", reply, "Envoyer"), title, reply))
        self.assertFalse(thread_screen(ui, snapshot(title, "Autre réponse", "Envoyer"), title, reply))
        self.assertTrue(thread_screen(ui, snapshot(title, reply, "Envoyer"), title, reply))
