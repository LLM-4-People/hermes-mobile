from dataclasses import dataclass
import hashlib
import json


@dataclass(frozen=True)
class Event:
    event_id: str
    kind: str
    created_at: float
    run_id: str | None = None
    session_id: str | None = None
    job_id: str | None = None

    @classmethod
    def make(cls, source, kind, created_at, **links):
        identity = json.dumps([kind, *source], separators=(",", ":"))
        event_id = hashlib.sha256(identity.encode()).hexdigest()[:32]
        return cls(event_id, kind, created_at, **links)
