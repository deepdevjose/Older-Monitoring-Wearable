"""Explicit factory for isolated assessment work. Never points itself at the live DB."""
from contextlib import asynccontextmanager
import asyncio
from datetime import datetime, timezone

from starlette.concurrency import run_in_threadpool

from . import account_auth, account_routes, fall_events, fall_routes
from .app import create_app, read_object
from .database import connect, transaction


def create_account_candidate(db_path, *, fall_worker_enabled=True, **options):
    if db_path is None or not str(db_path).strip():
        raise ValueError('An explicit isolated candidate database path is required')
    application = create_app(db_path, **options)
    target = application.state.db_path
    original_lifespan = application.router.lifespan_context
    application.state.fall_worker_status = {'state': 'not_started', 'last_checked_at': None}

    def sweep_once():
        with connect(target) as conn:
            fall_events.sweep_due(conn, datetime.now(timezone.utc))

    async def sweep_loop(stop):
        while not stop.is_set():
            try:
                await run_in_threadpool(sweep_once)
                application.state.fall_worker_status = {'state': 'running', 'last_checked_at': datetime.now(timezone.utc).isoformat()}
            except Exception as error:
                # Keep retrying durable events. Do not expose DB paths or exception contents.
                application.state.fall_worker_status = {'state': 'retrying', 'error_type': type(error).__name__}
            try:
                await asyncio.wait_for(stop.wait(), timeout=0.25)
            except asyncio.TimeoutError:
                pass

    @asynccontextmanager
    async def lifespan(app):
        async with original_lifespan(app):
            with connect(target) as conn, transaction(conn):
                account_auth.initialise(conn)
                fall_events.initialize(conn)
            stop = asyncio.Event()
            worker = asyncio.create_task(sweep_loop(stop)) if fall_worker_enabled else None
            if worker is None:
                application.state.fall_worker_status = {'state': 'disabled_for_isolated_tests'}
            try:
                yield
            finally:
                stop.set()
                if worker is not None:
                    await worker

    application.router.lifespan_context = lifespan
    authenticate = account_routes.register(application, target, read_object)
    fall_routes.register(application, target, read_object, authenticate)
    return application
