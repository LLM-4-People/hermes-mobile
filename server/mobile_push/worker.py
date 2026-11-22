from datetime import timedelta
import logging
import random
import sqlite3
import time
from .ledgers import HermesLedgers


log = logging.getLogger("hermes.mobile.push")


class FirebaseSender:
    def __init__(self, settings):
        import firebase_admin
        self.app = firebase_admin.initialize_app(options={
            "projectId": settings.firebase["projectId"], "httpTimeout": settings.send_timeout})
        self.retention = settings.retention

    def send(self, item):
        from firebase_admin import messaging
        data = {"event_id": item["event_id"], "installation_id": item["device_id"], "kind": item["kind"]}
        message = messaging.Message(token=item["token"], data=data,
                                    android=messaging.AndroidConfig(priority="high", ttl=timedelta(seconds=min(
                                        self.retention, 28 * 86400))))
        try:
            messaging.send(message, app=self.app)
        except messaging.UnregisteredError:
            return False
        return True


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
    try:
        active = sender.send(item)
    except Exception as exc:
        # Credentials, request URLs, payloads and provider error messages stay out of logs.
        log.warning("FCM delivery deferred (%s), attempt %d", type(exc).__name__, item["attempts"] + 1)
        cap = min(settings.retry_max, max(60, settings.poll_seconds * 2 ** min(item["attempts"], 20)))
        store.settle(item, retry_at=time.time() + random.uniform(max(1, cap / 2), cap))
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
