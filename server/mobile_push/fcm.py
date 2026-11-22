"""Send privacy-preserving Android data messages using Google's OAuth transport."""
from .provider import ProviderError, lifetime, opaque_notice, retry_seconds
import time
from urllib.parse import quote
import google.auth
from google.auth.transport.requests import AuthorizedSession


class FirebaseSender:
    def __init__(self, settings):
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        self.session = AuthorizedSession(credentials, refresh_timeout=settings.send_timeout)
        self.timeout = settings.send_timeout
        self.retention = settings.retention
        self.url = "https://fcm.googleapis.com/v1/projects/" + quote(settings.firebase["projectId"], safe="") + "/messages:send"

    def send(self, item):
        now = time.time()
        remaining = lifetime(item, self.retention, now)
        if remaining == 0:
            return True
        # With less than one second left, TTL zero requests immediate-only delivery.
        ttl = min(28 * 86400, int(remaining))
        data = opaque_notice(item)
        message = {"token": item["token"], "data": data, "android": {"priority": "high", "ttl": f"{ttl}s"}}
        with self.session.post(self.url, json={"message": message}, timeout=self.timeout,
                               max_allowed_time=self.timeout * 3, allow_redirects=False) as response:
            if response.ok and response.status_code == 200:
                return True
            if response.status_code == 404:
                try:
                    details = response.json()["error"]["details"]
                except (KeyError, ValueError, TypeError):
                    details = []
                if isinstance(details, list) and any(isinstance(d, dict)
                        and d.get("@type") == "type.googleapis.com/google.firebase.fcm.v1.FcmError"
                        and d.get("errorCode") == "UNREGISTERED" for d in details):
                    return False
            raise ProviderError(response.status_code, retry_seconds(response.headers.get("Retry-After")))
