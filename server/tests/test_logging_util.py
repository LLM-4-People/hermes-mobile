"""Unit tests for centralized server logging utility."""

import logging
import os
import unittest
from unittest.mock import patch

from server.logging_util import configure_logging, get_logger, resolve_level, safe_reason


class LoggingUtilTest(unittest.TestCase):
    def test_resolve_level_valid_fallback_and_invalid(self):
        with patch.dict(os.environ, {"HERMES_MOBILE_LOG_LEVEL": "debug"}, clear=True):
            self.assertEqual(resolve_level(), "DEBUG")
        with patch.dict(os.environ, {"MOBILE_PUSH_LOG_LEVEL": "ERROR", "HERMES_MOBILE_LOG_LEVEL": "DEBUG"}, clear=True):
            self.assertEqual(resolve_level(env_var="MOBILE_PUSH_LOG_LEVEL", fallback_env="HERMES_MOBILE_LOG_LEVEL"), "ERROR")
        with patch.dict(os.environ, {"HERMES_MOBILE_LOG_LEVEL": "NOT_A_LEVEL"}, clear=True):
            self.assertEqual(resolve_level(default="WARNING"), "WARNING")

    def test_get_logger_hierarchy_and_validation(self):
        self.assertEqual(get_logger().name, "hermes.mobile")
        self.assertEqual(get_logger("nearby").name, "hermes.mobile.nearby")
        for bad in ("bad space", "path/traversal", "line\nbreak", "a" * 65):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                get_logger(bad)

    def test_safe_reason_strips_exception_messages_and_secrets(self):
        exc = ValueError("Authorization: Bearer super-secret-token lat=45.51")
        self.assertEqual(safe_reason(exc), "ValueError")
        self.assertEqual(safe_reason(None), "unknown")

    def test_configure_logging_sets_component_level(self):
        with patch.dict(os.environ, {"HERMES_MOBILE_LOG_LEVEL": "DEBUG"}, clear=True):
            logger = configure_logging("artifacts")
            self.assertEqual(logger.name, "hermes.mobile.artifacts")
            self.assertEqual(logger.level, logging.DEBUG)
