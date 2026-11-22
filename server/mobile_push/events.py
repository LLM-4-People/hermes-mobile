from dataclasses import dataclass
import hashlib
import json


APPROVAL = "approval_requested"
QUESTION = "question_requested"
DISMISS_QUESTION = "question_settled"
DISMISS_APPROVAL = "approval_settled"
PROMPT_KINDS = (APPROVAL, QUESTION)
WITHDRAWAL_KINDS = (DISMISS_APPROVAL, DISMISS_QUESTION)


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


@dataclass(frozen=True)
class Observation:
    updated_at: float
    events: tuple[Event, ...]
    # When supplied, the observed run owns the complete set of pending prompts.
    run_id: str | None = None
