"""Loopback-only dashboard; the existing authenticated receiver remains separate."""
from __future__ import annotations

from contextlib import asynccontextmanager
import asyncio
import ipaddress
import os
from pathlib import Path
import secrets
import sqlite3
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Path as ApiPath, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from starlette.staticfiles import StaticFiles

from .store import DEFAULT_DATABASE, STALE_SECONDS, Store, utc_now
from .capture import CaptureStore, MAX_EXPORT

MAX_SQLITE_ID = 2**63 - 1


class NewSession(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=100)
    participant_code: str = Field(min_length=1, max_length=40, pattern=r"^[A-Za-z0-9_-]+$")
    device_id: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_.:-]+$")
    firmware_version: str = Field(min_length=1, max_length=60)
    placement: str = Field(min_length=1, max_length=100)

    @field_validator("name", "firmware_version", "placement")
    @classmethod
    def clean_text(cls, value):
        value = value.strip()
        if not value or any(ord(char) < 32 for char in value):
            raise ValueError("Use a short single-line description.")
        return value


class NewCapture(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_.:-]+$")
    name: str | None = Field(None, min_length=1, max_length=100)

    @field_validator("name")
    @classmethod
    def clean_name(cls, value):
        if value is None:
            return None
        value = value.strip()
        if not value or any(ord(char) < 32 for char in value):
            raise ValueError("Use a short single-line recording name.")
        return value


def create_app(db_path=DEFAULT_DATABASE, *, clock=utc_now, port=8001, stale_seconds=STALE_SECONDS,
               capture_worker=True):
    store = Store(db_path, clock=clock, stale_seconds=stale_seconds)
    captures = CaptureStore(store)
    csrf_token = secrets.token_urlsafe(32)

    @asynccontextmanager
    async def lifespan(app):
        store.initialize()
        captures.initialize()
        stop_worker = asyncio.Event()

        async def collect():
            while not stop_worker.is_set():
                try:
                    await asyncio.to_thread(captures.scan_pending)
                except (OSError, sqlite3.Error):
                    # A temporary lock or unavailable DB is retried. Raw errors
                    # and sensor payloads are deliberately not logged.
                    pass
                try:
                    await asyncio.wait_for(stop_worker.wait(), timeout=1)
                except asyncio.TimeoutError:
                    pass

        task = asyncio.create_task(collect()) if capture_worker else None
        try:
            yield
        finally:
            stop_worker.set()
            if task:
                await task

    app = FastAPI(title="Care Monitor Live Test Database", docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)
    app.state.store = store
    app.state.captures = captures
    app.state.port = port

    @app.middleware("http")
    async def local_only(request, call_next):
        def reject(message, status=403):
            return JSONResponse({"detail": message}, status_code=status)
        try:
            address = ipaddress.ip_address(request.client.host if request.client else "")
            if not address.is_loopback:
                return reject("This dashboard is available only on this computer.")
        except ValueError:
            return reject("A loopback client address is required.")
        host = request.headers.get("host", "")
        try:
            parsed = urlsplit("http://" + host)
            if parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.port != port or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
                return reject("Unrecognised dashboard host.")
        except ValueError:
            return reject("Invalid dashboard host.")
        origin = request.headers.get("origin")
        expected_origin = "http://" + host
        if origin is not None and origin != expected_origin:
            return reject("Cross-origin dashboard access is blocked.")
        if request.method not in {"GET", "HEAD", "OPTIONS"}:
            if origin != expected_origin:
                return reject("A same-origin browser request is required.")
            if not secrets.compare_digest(request.headers.get("x-csrf-token", ""), csrf_token):
                return reject("Refresh the dashboard before changing a test session.")
        if request.method == "OPTIONS":
            return reject("Cross-origin requests are not enabled.")
        response = await call_next(request)
        response.headers.update({
            "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
            "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
        })
        return response

    @app.exception_handler(LookupError)
    async def missing_session(request, exc):
        return JSONResponse({"detail": str(exc)}, status_code=404)

    @app.exception_handler(ValueError)
    async def invalid_selection(request, exc):
        return JSONResponse({"detail": str(exc)}, status_code=409)

    @app.exception_handler(sqlite3.Error)
    async def database_error(request, exc):
        return JSONResponse({"detail": "Database temporarily unavailable. The receiver and saved readings were not changed."}, status_code=503)

    @app.exception_handler(FileNotFoundError)
    async def database_missing(request, exc):
        return JSONResponse({"detail": "The existing receiver database cannot be found. Check the dashboard database path."}, status_code=503)

    @app.get("/health")
    @app.get("/api/health")
    def health():
        with store.connection() as conn:
            conn.execute("SELECT id FROM cm_readings LIMIT 1").fetchone()
        return {"status": "ok", "service": "care-monitor-test-dashboard", "database": store.path.name, "local_only": True}

    @app.get("/api/bootstrap")
    def bootstrap():
        return {"csrf_token": csrf_token, "refresh_ms": 1000, "stale_after_seconds": stale_seconds}

    @app.get("/api/status")
    @app.get("/api/snapshot")
    def snapshot(device_id: str | None = Query(None, max_length=80), session_id: int | None = Query(None, ge=1, le=MAX_SQLITE_ID)):
        return store.status(device_id, session_id)

    @app.get("/api/devices")
    def devices():
        return store.devices()

    @app.get("/api/records")
    def records(device_id: str | None = Query(None, max_length=80), session_id: int | None = Query(None, ge=1, le=MAX_SQLITE_ID),
                before_id: int | None = Query(None, ge=1, le=MAX_SQLITE_ID), limit: int = Query(100, ge=1, le=500)):
        return store.records(device_id, session_id, before_id, limit)

    @app.get("/api/sessions")
    def sessions():
        return store.sessions()

    @app.get("/api/captures")
    def list_captures(device_id: str | None = Query(None, max_length=80), limit: int = Query(100, ge=1, le=100)):
        return captures.list(device_id, limit)

    @app.get("/api/captures/{recording_id}")
    def get_capture(recording_id: int = ApiPath(..., ge=1, le=MAX_SQLITE_ID)):
        return captures.get(recording_id)

    @app.post("/api/captures", status_code=201)
    async def start_capture(request: Request):
        if request.headers.get("content-type", "").split(";", 1)[0].strip() != "application/json":
            raise HTTPException(415, "Use a JSON request body.")
        length = request.headers.get("content-length", "0")
        if not length.isdigit() or int(length) > 4096:
            raise HTTPException(413, "Recording description is too large.")
        raw = await request.body()
        if len(raw) > 4096:
            raise HTTPException(413, "Recording description is too large.")
        try:
            metadata = NewCapture.model_validate_json(raw)
        except ValidationError:
            raise HTTPException(422, "Select a device ID and an optional short recording name.")
        return await asyncio.to_thread(captures.start, metadata.device_id, metadata.name)

    @app.post("/api/captures/{recording_id}/stop")
    def stop_capture(recording_id: int = ApiPath(..., ge=1, le=MAX_SQLITE_ID)):
        return captures.stop(recording_id)

    @app.get("/api/captures/{recording_id}/export.csv")
    def export_capture(recording_id: int = ApiPath(..., ge=1, le=MAX_SQLITE_ID), limit: int = Query(MAX_EXPORT, ge=1, le=MAX_EXPORT)):
        data, maximum, truncated, count = captures.export(recording_id, limit)
        return Response(data, media_type="text/csv; charset=utf-8", headers={
            "Content-Disposition": f'attachment; filename="care-monitor-valid-data-{recording_id}.csv"',
            "X-Export-Snapshot-Max-Id": str(maximum), "X-Export-Truncated": str(truncated).lower(),
            "X-Export-Record-Count": str(count), "X-Capture-Policy-Version": "1",
        })

    @app.post("/api/sessions", status_code=201)
    async def start_session(request: Request):
        if request.headers.get("content-type", "").split(";", 1)[0].strip() != "application/json":
            raise HTTPException(415, "Use a JSON request body.")
        length = request.headers.get("content-length", "0")
        if not length.isdigit() or int(length) > 4096:
            raise HTTPException(413, "Test-session description is too large.")
        raw = await request.body()
        if len(raw) > 4096:
            raise HTTPException(413, "Test-session description is too large.")
        try:
            metadata = NewSession.model_validate_json(raw)
        except ValidationError:
            raise HTTPException(422, "Enter a name, pseudonymous participant code, device ID, firmware version and placement; use the displayed length limits.")
        return store.start_session(metadata.model_dump())

    @app.post("/api/sessions/{session_id}/stop")
    def stop_session(session_id: int = ApiPath(..., ge=1, le=MAX_SQLITE_ID)):
        return store.stop_session(session_id)

    def csv_response(session_id, limit):
        data, maximum, truncated, count = store.export(session_id, limit)
        return Response(data, media_type="text/csv; charset=utf-8", headers={
            "Content-Disposition": f'attachment; filename="care-monitor-session-{session_id}.csv"',
            "X-Export-Snapshot-Max-Id": str(maximum), "X-Export-Truncated": str(truncated).lower(),
            "X-Export-Record-Count": str(count),
        })

    @app.get("/api/sessions/{session_id}/export.csv")
    def export_session(session_id: int = ApiPath(..., ge=1, le=MAX_SQLITE_ID), limit: int = Query(10000, ge=1, le=20000)):
        return csv_response(session_id, limit)

    @app.get("/api/export.csv")
    def export_alias(session_id: int = Query(..., ge=1, le=MAX_SQLITE_ID), limit: int = Query(10000, ge=1, le=20000)):
        return csv_response(session_id, limit)

    static = Path(__file__).parent / "static"

    @app.get("/", include_in_schema=False)
    def home():
        if not (static / "index.html").is_file():
            raise HTTPException(503, "Dashboard frontend files are not installed.")
        return FileResponse(static / "index.html")

    if static.is_dir():
        app.mount("/static", StaticFiles(directory=static), name="static")
    return app


app = create_app(os.environ.get("CARE_MONITOR_DB", DEFAULT_DATABASE))
