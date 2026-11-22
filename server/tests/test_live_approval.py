"""A real loopback Hermes run, mobile bridge, and captured FCM transport."""
import asyncio
import os
import sys
import time
import unittest
from contextlib import contextmanager, ExitStack
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch

from aiohttp.test_utils import TestClient, TestServer
from server.mobile_push.fcm import FirebaseSender
from server.mobile_push.ledgers import HermesLedgers
from server.mobile_push.worker import deliver
from support import fixture


@unittest.skipUnless(os.environ.get("HERMES_TEST_CHECKOUT"), "requires a matching fork test environment")
class CanonicalPush(unittest.IsolatedAsyncioTestCase):
    async def test_approval_and_completion_reach_fcm_with_only_opaque_ids(self):
        await self.exercise_control(False)

    async def test_question_and_its_dismissal_reach_fcm_with_only_opaque_ids(self):
        await self.exercise_control(True)

    async def exercise_control(self, question):
        kind = "question_requested" if question else "approval_requested"
        checkout = os.environ["HERMES_TEST_CHECKOUT"]
        with ExitStack() as stack:
            root = stack.enter_context(TemporaryDirectory())
            stack.enter_context(patch.dict(os.environ, {"HERMES_HOME":root, "HOME":root}, clear=True))
            stack.enter_context(patch.object(sys, "path", [checkout, *sys.path]))
            # The disposable home has no scratch history to prune in a detached process.
            stack.enter_context(patch("hermes_constants_scratch_detach.spawn_prune"))
            from pytest import MonkeyPatch
            from tests.gateway.conftest import api, owner
            from tests.gateway.test_api_server_runs import _create_runs_app
            from tests.gateway.test_chat_completions_approval import _canonical_approval_turn
            from tests.waiting import async_wait_until

            settings, store = fixture(root)
            device_id = "00000000-0000-4000-8000-000000000001"
            store.device(device_id, settings.profile_key, profile=True, now=time.time() - 1,
                         token="test-token", management_key="d"*64)
            mp = stack.enter_context(MonkeyPatch.context())
            authority = stack.enter_context(contextmanager(owner.__wrapped__)(Path(root)))
            adapter = stack.enter_context(contextmanager(api.__wrapped__)(authority, Path(root), mp))
            adapter._api_key = settings.profile_key
            mp.setattr("tools.approval_context._get_approval_timeout", lambda: 15)
            decisions = []
            if question:
                from gateway.session_results import execution_result
                from tools import clarify_gateway
                from tests.gateway.test_chat_completions_approval import session_row

                async def ask(event):
                    session = event.source.chat_id
                    generation = session_row(authority.persistence, session)["runtime_generation"]
                    entry = clarify_gateway.register("test-question", "api", "Private question?", ["Private option"])
                    authority.register_clarify(session, generation, entry)
                    await asyncio.to_thread(entry.event.wait, 10)
                    decisions.append(entry.response)
                    execution_result.get()["result"] = {"final_response": "done"}
                    return "done"
                authority.runner._handle_message = ask
            else:
                authority.runner._handle_message = _canonical_approval_turn(authority, decisions)
            ledgers = HermesLedgers(settings)
            stack.callback(ledgers.close)
            with patch("server.mobile_push.fcm.google.auth.default", return_value=(MagicMock(), None)):
                sender = FirebaseSender(settings)
            stack.callback(sender.session.close)
            post = stack.enter_context(patch.object(sender.session, "post"))
            post.return_value.__enter__.return_value.status_code = 200

            def find(kind):
                ledgers.runs.scan((settings.run_scope, 0), store, 0)
                return next((event for event in store.events()["events"] if event["kind"] == kind), None)

            app = _create_runs_app(adapter)
            if question and not any(r.resource.canonical.endswith("/clarify") for r in app.router.routes()):
                app.router.add_post("/v1/runs/{run_id}/clarify", adapter._handle_run_clarify)
            async with TestClient(TestServer(app)) as client:
                headers = {"Authorization":"Bearer " + settings.profile_key, "Idempotency-Key":"push-proof"}
                created = await client.post("/v1/runs", headers=headers,
                                            json={"input":"request approval", "session_id":"mobile-proof"})
                self.assertEqual(created.status, 202, await created.text())
                run_id = (await created.json())["run_id"]
                endpoint = "/v1/runs/" + run_id
                requested = await async_wait_until(lambda: find(kind), what="owned prompt notification")
                current = await client.get(endpoint, headers=headers)
                prompt = (await current.json())["pending_controls"][0]
                authority_task = authority.sessions["mobile-proof"].task
                deliver(store.claim(time.time(), 30), settings, store, sender)
                ledgers.close()
                self.assertEqual(find(kind)["event_id"], requested["event_id"])
                self.assertIsNone(store.claim(time.time(), 30))
                body = {"request_id": prompt["prompt_id"], "execution_generation": prompt["execution_generation"]}
                body.update({"answer": "Private answer"} if question else {"choice": "deny"})
                answered = await client.post(endpoint + ("/clarify" if question else "/approval"),
                                             headers=headers, json=body)
                self.assertEqual(answered.status, 200, await answered.text())
                final = await async_wait_until(lambda: find("run_completed"), what="private completion event")
                expected_events = [requested]
                withdrawal_kind = "question_settled" if question else "approval_settled"
                dismissed = store.claim(time.time(), 30)
                self.assertEqual((dismissed["kind"], dismissed["event_id"]), (withdrawal_kind, requested["event_id"]))
                deliver(dismissed, settings, store, sender)
                expected_events.append({**requested, "kind": withdrawal_kind})
                deliver(store.claim(time.time(), 30), settings, store, sender)
                await asyncio.wait_for(authority_task, 10)
                self.assertEqual(decisions[0] if question else decisions[0]["choice"],
                                 "Private answer" if question else "deny")
                self.assertEqual([requested["run_id"], final["run_id"]], [run_id, run_id])
                self.assertEqual(requested["session_id"], "mobile-proof")
                expected = [{"installation_id":device_id, "event_id":event["event_id"], "kind":event["kind"]}
                            for event in [*expected_events, final]]
                self.assertEqual([c.kwargs["json"]["message"]["data"] for c in post.call_args_list], expected)
