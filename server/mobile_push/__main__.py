import asyncio
import os
from threading import Event, Thread
from aiohttp import web
from .api import build_app
from .config import Settings
from .store import Store
from .worker import scan_loop, send_loop
from .transports import Transports

try:
    from server.logging_util import configure_logging
except ImportError:
    from logging_util import configure_logging


def main():
    os.umask(0o077)
    configure_logging("push", env_var="MOBILE_PUSH_LOG_LEVEL", fallback_env="HERMES_MOBILE_LOG_LEVEL")
    settings = Settings.load()
    store = Store(settings.state)
    sender = Transports(settings)
    app = build_app(settings, store)

    async def workers(app):
        stopped, changed = Event(), Event()
        threads = [Thread(target=scan_loop, args=(settings, store, stopped, changed), name="hermes-events"),
                   Thread(target=send_loop, args=(settings, store, sender, stopped, changed), name="push-delivery")]
        for thread in threads:
            thread.start()
        try:
            yield
        finally:
            stopped.set()
            changed.set()
            await asyncio.gather(*(asyncio.to_thread(thread.join) for thread in threads))
            sender.close()

    app.cleanup_ctx.append(workers)
    web.run_app(app, host=os.environ.get("MOBILE_PUSH_BIND", "127.0.0.1"), port=settings.port,
                access_log=None)


if __name__ == "__main__":
    main()
