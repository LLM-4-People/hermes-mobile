"""Exercise the real Hermes ledger writers in a throwaway home when a checkout is supplied."""
import os
import sys
import time
import unittest
from tempfile import TemporaryDirectory
from unittest.mock import patch
from server.mobile_push.ledgers import HermesLedgers
from support import fixture


@unittest.skipUnless(os.environ.get("HERMES_TEST_CHECKOUT"), "set HERMES_TEST_CHECKOUT to the target fork checkout")
class HermesContract(unittest.TestCase):
    def test_real_run_reservation_terminal_state_and_started_cron(self):
        with TemporaryDirectory() as directory, patch.dict(os.environ, HERMES_HOME=directory), \
             patch.object(sys, "path", [os.environ["HERMES_TEST_CHECKOUT"], *sys.path]):
            from gateway.platforms.api_server_run_idempotency import RunIdempotencyStore
            from cron import executions
            settings, store = fixture(directory)
            store.device("test-phone", settings.profile_key, profile=True, now=time.time() - 1,
                         token="unused-token", management_key="1"*64)
            owner = RunIdempotencyStore(str(settings.home / "runs_idempotency.db"))
            try:
                run = {"status":"queued", "run_id":"run_accepted", "session_id":"session"}
                outcome, _ = owner.reserve(settings.run_scope, "request", "fingerprint", run["run_id"], run)
                self.assertEqual(outcome, "created")
                owner.update_status(run["run_id"], run | {"status":"completed", "output":"private transcript"})
                cron_db = settings.home / "cron" / "executions.db"
                with patch.object(executions, "EXECUTIONS_FILE", cron_db):
                    occurrence = executions.create_execution("job-id", source="scheduler")
                    self.assertEqual(executions.mark_execution_running(occurrence["id"])["status"], "running")
                sources = HermesLedgers(settings)
                try:
                    for ledger, args in sources.sources(0):
                        ledger.scan(args, store, 0)
                    public = store.events()["events"]
                    self.assertEqual([row["kind"] for row in public], ["run_completed","reminder"])
                    self.assertEqual(public[0]["session_id"], "session")
                    self.assertEqual(public[1]["job_id"], "job-id")
                    self.assertNotIn("private transcript", repr(public))
                    self.assertEqual(store.claim(time.time(), 30)["event_id"], public[0]["event_id"])
                finally:
                    sources.close()
            finally:
                owner.close()
