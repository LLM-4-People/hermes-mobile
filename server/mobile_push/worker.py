import logging
import random
import sqlite3
import time
from .events import PROMPT_KINDS
from .ledgers import HermesLedgers
from .provider import ProviderError


log = logging.getLogger("hermes.mobile.push")


def scan_loop(settings, store, stopped, changed):
    sources = HermesLedgers(settings)
    try:
        while not stopped.is_set():
            cutoff = time.time() - settings.retention
            for ledger, params in sources.sources(cutoff):
                try:
                    ledger.scan(params, store, cutoff)
                except (sqlite3.Error, OSError, ValueError) as exc:
                    log.error("Cannot reconcile %s (%s)", ledger.path.name, type(exc).__name__)
            try:
                store.expire(cutoff)
            except sqlite3.Error as exc:
                log.error("Cannot expire old events (%s)", type(exc).__name__)
            changed.set()
            stopped.wait(settings.poll_seconds)
    finally:
        sources.close()


def deliver(item, settings, store, sender):
    if item["kind"] in PROMPT_KINDS:
        # The sender can recover an outstanding lease before the next (or first) scan.
        # Verify the exact scoped prompt again before its network request begins.
        try:
            pending = HermesLedgers(settings).prompt_pending(item)
        except (OSError, sqlite3.Error, ValueError) as exc:
            log.warning("Cannot confirm pending prompt (%s)", type(exc).__name__)
            store.settle(item, retry_at=time.time() + settings.poll_seconds)
            return
        if not pending:
            store.settle(item)
            return
    if not store.begin_send(item):
        return
    try:
        active = sender.send(item)
    except Exception as exc:
        # Credentials, request URLs, payloads and provider error messages stay out of logs.
        cap = min(settings.retry_max, max(60, settings.poll_seconds * 2 ** min(item["attempts"], 20)))
        backoff = random.uniform(max(1, cap / 2), cap)
        provider = item["provider"] if item["provider"] in ("fcm", "unifiedpush") else "unknown"
        reason, status = type(exc).__name__, "local"
        if isinstance(exc, ProviderError):
            backoff = max(min(exc.retry_after, settings.retention), backoff)
            reason = exc.reason.value
            if isinstance(exc.status, int):
                status = exc.status
        log.warning("Push deferred provider=%s code=%s status=%s attempt=%d retry_seconds=%.2f",
                    provider, reason, status, item["attempts"] + 1, backoff)
        store.settle(item, retry_at=time.time() + backoff)
    else:
        store.settle(item, unregistered=not active)


def send_loop(settings, store, sender, stopped, changed):
    while not stopped.is_set():
        try:
            item = store.claim(time.time(), settings.send_timeout * 12 + 30)
            if item:
                deliver(item, settings, store, sender)
                continue
        except sqlite3.Error as exc:
            log.error("Push outbox unavailable (%s)", type(exc).__name__)
        changed.wait(settings.poll_seconds)
        changed.clear()
