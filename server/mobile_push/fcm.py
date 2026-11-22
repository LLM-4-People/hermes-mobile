"""Send privacy-preserving Android data messages using Google's OAuth transport."""
from email.utils import parsedate_to_datetime
import time
from urllib.parse import quote
import google.auth
from google.auth.transport.requests import AuthorizedSession


class ProviderError(Exception):
    def __init__(self, status, retry_after):
        super().__init__(f"FCM HTTP {status}")
        self.retry_after = retry_after


def retry_seconds(header):
    if not header:
        return 0
    try:
        return max(0, int(header))
    except ValueError:
        try:
            return max(0, parsedate_to_datetime(header).timestamp() - time.time())
        except (ValueError, TypeError, OverflowError):
            return 0


class FirebaseSender:
    def __init__(self, settings):
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/firebase.messaging"])
        self.session = AuthorizedSession(credentials, refresh_timeout=settings.send_timeout)
        self.timeout = settings.send_timeout
        self.retention = settings.retention
        self.url = "https://fcm.googleapis.com/v1/projects/" + quote(settings.firebase["projectId"], safe="") + "/messages:send"

    def send(self, item):
        now = time.time()
        ttl = max(0, min(28 * 86400, int(item["created_at"] + self.retention - now)))
        data = {"event_id": item["event_id"], "installation_id": item["device_id"], "kind": item["kind"]}
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
