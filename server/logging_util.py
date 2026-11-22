"""Centralized privacy-safe structured logging for all Hermes Mobile server plugins."""

import logging
import os
import re

ROOT_LOGGER = "hermes.mobile"
FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"
_SAFE_TOKEN = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
_LEVELS = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}


def resolve_level(env_var="HERMES_MOBILE_LOG_LEVEL", fallback_env=None, default="INFO"):
    """Resolve a validated logging level from environment variables without crashing on bad input."""
    for name in (env_var, fallback_env):
        if not name:
            continue
        raw = os.environ.get(name, "").strip().upper()
        if raw in _LEVELS:
            return raw
    return default if default in _LEVELS else "INFO"


def configure_logging(component=None, env_var="HERMES_MOBILE_LOG_LEVEL", fallback_env=None, default="INFO"):
    """Configure root handler format once and set the component logger level."""
    logging.basicConfig(level=logging.WARNING, format=FORMAT)
    logger = get_logger(component)
    logger.setLevel(resolve_level(env_var=env_var, fallback_env=fallback_env, default=default))
    return logger


def get_logger(component=None):
    """Return a logger under the shared hermes.mobile hierarchy."""
    if not component:
        return logging.getLogger(ROOT_LOGGER)
    cleaned = str(component).strip().lower()
    if not _SAFE_TOKEN.fullmatch(cleaned):
        raise ValueError("Invalid logging component name")
    return logging.getLogger(f"{ROOT_LOGGER}.{cleaned}")


def safe_reason(exc):
    """Extract only the bounded exception class name; never log credentials, URLs, coordinates or bodies."""
    if exc is None:
        return "unknown"
    name = type(exc).__name__
    return name if _SAFE_TOKEN.fullmatch(name) else "error"
