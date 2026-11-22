from .fcm import FirebaseSender
from .provider import ProviderError
from .webpush import WebPushSender


class Transports:
    def __init__(self, settings):
        self.senders = {}
        if settings.firebase:
            self.senders["fcm"] = FirebaseSender(settings)
        if settings.webpush:
            self.senders["unifiedpush"] = WebPushSender(settings)

    def send(self, item):
        sender = self.senders.get(item["provider"])
        if sender is None:
            # Retain the subscription across temporary provider maintenance.
            raise ProviderError(503)
        return sender.send(item)

    def close(self):
        for sender in self.senders.values():
            sender.session.close()
