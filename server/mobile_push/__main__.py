import asyncio
import logging
import os
from threading import Event, Thread
from aiohttp import web
from .api import build_app
from .config import Settings
from .store import Store
from .worker import FirebaseSender, scan_loop, send_loop


def main():
    os.umask(0o077)
    logging.basicConfig(level=os.environ.get("MOBILE_PUSH_LOG_LEVEL", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.load()
    store = Store(settings.state)
    sender = FirebaseSender(settings)
    app = build_app(settings, store)

    async def workers(app):
        stopped, changed = Event(), Event()
        threads = [Thread(target=scan_loop, args=(settings, store, stopped, changed), name="hermes-events"),
                   Thread(target=send_loop, args=(settings, store, sender, stopped, changed), name="fcm-delivery")]
        for thread in threads:
            thread.start()
        try:
            yield
        finally:
            stopped.set()
            changed.set()
            await asyncio.gather(*(asyncio.to_thread(thread.join) for thread in threads))

    app.cleanup_ctx.append(workers)
    web.run_app(app, host=os.environ.get("MOBILE_PUSH_BIND", "127.0.0.1"), port=settings.port,
                access_log=None)


if __name__ == "__main__":
    main()
