"""HTTPS-only phone ingest, credential verification and durable alert APIs."""
from __future__ import annotations

from contextlib import asynccontextmanager
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import sqlite3
import time
from typing import Any

from fastapi import FastAPI, HTTPException, Path as PathParameter, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import __version__, alert_engine
from .database import connect, default_path, initialise, server_id, transaction
from .classification import CandidateClassifier, InvalidClassificationInput, validate_request as validate_classification
from .security import Credential, IDENTIFIER, audit, failed_limit_active, lookup, take_limit, utc_timestamp

MAX_BODY_BYTES = 16 * 1024
SENSOR_NAMES = ("max30102","mpu6050","gy906","photoresistor","buzzer")
# Older packets remain valid historical records; removed modules are not advertised.
SNAPSHOT_BLOCK_NAMES = SENSOR_NAMES + ("microphone",)


@dataclass(frozen=True)
class Limits:
    failed_auth: int = 10
    credential_requests: int = 240
    window_seconds: int = 60


class RequestGuard:
    """ASGI body bound counts bytes even without Content-Length or with chunking."""
    def __init__(self, app: Any) -> None:
        self.app = app

    async def __call__(self, scope: dict, receive: Any, send: Any) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        async def reject(status: int, detail: str) -> None:
            await JSONResponse({"detail":detail}, status_code=status,
                               headers={"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"})(scope,receive,send)
        # Forwarded/X-Forwarded-Proto headers do not change scope; start uvicorn with --no-proxy-headers.
        if scope["path"] not in {"/","/health"} and scope.get("scheme") != "https":
            await reject(426,"HTTPS is required")
            return
        lengths = [v for k,v in scope["headers"] if k.lower() == b"content-length"]
        if lengths:
            try:
                if len(lengths) != 1 or not re.fullmatch(rb"[0-9]+",lengths[0]):
                    raise ValueError
                if int(lengths[0]) > MAX_BODY_BYTES:
                    await reject(413,"Request body is too large")
                    return
            except ValueError:
                await reject(400,"Invalid Content-Length")
                return
        chunks: list[bytes] = []
        size = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body",b"")
            size += len(chunk)
            if size > MAX_BODY_BYTES:
                await reject(413,"Request body is too large")
                return
            chunks.append(chunk)
            if not message.get("more_body",False):
                break
        body = b"".join(chunks)
        delivered = False
        async def bounded_receive() -> dict:
            nonlocal delivered
            if delivered:
                return await receive()
            delivered = True
            return {"type":"http.request","body":body,"more_body":False}
        async def guarded_send(message: dict) -> None:
            if message["type"] == "http.response.start":
                message["headers"] = list(message.get("headers",[])) + [
                    (b"cache-control",b"no-store"),(b"x-content-type-options",b"nosniff")]
            await send(message)
        await self.app(scope,bounded_receive,guarded_send)


def _invalid_json_constant(_: str) -> None:
    raise ValueError("Non-finite value")


def _object_pairs(pairs: list[tuple[str,Any]]) -> dict:
    result = {}
    for name,value in pairs:
        if name in result:
            raise ValueError("Duplicate field")
        result[name] = value
    return result


async def read_object(request: Request) -> dict[str,Any]:
    try:
        result = json.loads((await request.body()).decode("utf-8"),
                            parse_constant=_invalid_json_constant,object_pairs_hook=_object_pairs)
        if not isinstance(result,dict):
            raise ValueError
        return result
    except (ValueError,UnicodeError,RecursionError):
        raise HTTPException(400,"A valid JSON object is required") from None


def validate_snapshot(snapshot: dict) -> tuple[str | None,str | None]:
    """Keep firmware fields intact; validate structure, finite numbers and dedup identity."""
    if not isinstance(snapshot.get("device_id"),str) or not IDENTIFIER.fullmatch(snapshot["device_id"]):
        raise HTTPException(400,"A valid device_id is required")
    for name in SNAPSHOT_BLOCK_NAMES:
        if name in snapshot and snapshot[name] is not None and not isinstance(snapshot[name],dict):
            raise HTTPException(400,"Sensor blocks must be JSON objects or null")
    # Limit nesting and rule out numbers that overflow during SQLite or model conversion.
    def walk(value: Any, depth: int = 0) -> None:
        if depth > 12:
            raise HTTPException(400,"Snapshot nesting is too deep")
        if isinstance(value,float) and not math.isfinite(value):
            raise HTTPException(400,"Snapshot numbers must be finite")
        if isinstance(value,int) and not isinstance(value,bool) and abs(value) > 2**63-1:
            raise HTTPException(400,"Snapshot number is out of range")
        if isinstance(value,dict):
            for child in value.values(): walk(child,depth+1)
        elif isinstance(value,list):
            for child in value: walk(child,depth+1)
    walk(snapshot)
    uptime = snapshot.get("uptime_ms")
    if uptime is not None and (not isinstance(uptime,int) or isinstance(uptime,bool) or uptime < 0):
        raise HTTPException(400,"uptime_ms must be a nonnegative integer")
    if snapshot.get("timestamp") is not None and (not isinstance(snapshot["timestamp"],str) or len(snapshot["timestamp"])>80):
        raise HTTPException(400,"Invalid sensor timestamp")
    present = "sample_id" in snapshot or "boot_nonce" in snapshot
    if not present:
        return None,None
    # Both must be supplied to opt into idempotency. Do not merge arbitrary packets by uptime.
    sample = snapshot.get("sample_id")
    nonce = snapshot.get("boot_nonce")
    if isinstance(sample,int) and not isinstance(sample,bool) and sample >= 0:
        sample = str(sample)
    if not isinstance(sample,str) or not IDENTIFIER.fullmatch(sample) or not isinstance(nonce,str) or not IDENTIFIER.fullmatch(nonce):
        raise HTTPException(400,"sample_id and boot_nonce must both be nonempty identifiers")
    return nonce,sample


def create_app(db_path: Path | str | None = None, *, limits: Limits | None = None,
               classification_manifest: Path | str | None = None,
               classification_manifest_sha256: str | None = None) -> FastAPI:
    target = Path(db_path) if db_path is not None else default_path()
    policy = limits or Limits()
    if min(policy.failed_auth,policy.credential_requests,policy.window_seconds) < 1:
        raise ValueError("Rate limits must be positive")

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        initialise(target)
        yield

    application = FastAPI(title="Care Monitor API",version=__version__,lifespan=lifespan,
                          docs_url=None,redoc_url=None,openapi_url=None)
    application.add_middleware(RequestGuard)
    application.state.db_path = target
    classifier = CandidateClassifier(classification_manifest, classification_manifest_sha256)
    application.state.classifier = classifier

    @application.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        return JSONResponse({"detail":"Invalid request parameters"},status_code=422)

    @application.exception_handler(Exception)
    async def storage_error(request: Request, exc: Exception):
        # Do not expose SQLite, local file paths, submitted data, credentials, or stack traces.
        return JSONResponse({"detail":"Server could not complete the request"},status_code=500)

    def authenticate(request: Request, scope: str | None = None, device_id: str | None = None) -> Credential:
        now = int(time.time())
        ip = request.client.host if request.client else "unknown"
        error: HTTPException | None = None
        credential = None
        # Authentication and limits commit on denial as well as on success.
        with connect(target) as conn, transaction(conn):
            if failed_limit_active(conn,ip,now,capacity=policy.failed_auth,window_seconds=policy.window_seconds):
                error = HTTPException(429,"Too many authentication attempts",headers={"Retry-After":str(policy.window_seconds)})
            else:
                headers = request.headers.getlist("x-api-key")
                credential = lookup(conn,headers[0] if len(headers)==1 else None,now)
                if credential is None:
                    allowed,retry = take_limit(conn,"failed-auth",ip,now,capacity=policy.failed_auth,window_seconds=policy.window_seconds)
                    # Failed attempts are intentionally not individually logged; counters avoid log flooding.
                    error = HTTPException(401,"Invalid or expired credential") if allowed else HTTPException(429,"Too many authentication attempts",headers={"Retry-After":str(retry)})
                else:
                    allowed,retry = take_limit(conn,"credential",credential.key_id,now,capacity=policy.credential_requests,window_seconds=policy.window_seconds)
                    if not allowed:
                        error = HTTPException(429,"Request rate limit exceeded",headers={"Retry-After":str(retry)})
                    elif scope is not None and scope not in credential.scopes:
                        error = HTTPException(403,"Credential does not permit this operation")
                    elif device_id is not None and device_id != credential.device_id:
                        error = HTTPException(403,"Credential is not authorised for this device")
        if error is not None:
            raise error
        assert credential is not None
        return credential

    @application.get("/")
    @application.get("/health")
    def health() -> dict:
        return {"status":"ok","service":"care-monitor"}

    @application.get("/auth/status")
    def auth_status(request: Request, device_id: str | None = None) -> dict:
        credential = authenticate(request,device_id=device_id)
        with connect(target) as conn:
            identity = server_id(conn)
        return {"status":"verified","key_id":credential.key_id,"device_id":credential.device_id,
                "scopes":sorted(credential.scopes),"expires_at":utc_timestamp(credential.expires_at),"server_id":identity}

    @application.post("/ml/classify")
    async def classify_measurements(request: Request):
        credential = authenticate(request, "sensor:read")
        body = await read_object(request)
        try:
            device, task, values, source = validate_classification(body)
            if device != credential.device_id:
                raise HTTPException(403, "Credential is not authorised for this device")
            if classifier.status != "ready":
                return JSONResponse({"status": classifier.status,
                    "reason": "Classification requires explicitly configured, verified assessment artifacts",
                    "classification": None, "confidence": None, "three_class_supported": False,
                    "clinical_validation": False, "care_alert_created": False}, status_code=503)
            return classifier.classify(device, task, values, source)
        except InvalidClassificationInput as exc:
            raise HTTPException(400, str(exc)) from None

    @application.post("/sensor-data")
    async def receive_snapshot(request: Request) -> dict:
        credential = authenticate(request,"sensor:write")
        snapshot = await read_object(request)
        nonce,sample = validate_snapshot(snapshot)
        if snapshot["device_id"] != credential.device_id:
            raise HTTPException(403,"Credential is not authorised for this device")
        device_id = credential.device_id
        received_at = utc_timestamp()
        serialised = json.dumps(snapshot,separators=(",",":"),sort_keys=True,allow_nan=False)
        with connect(target) as conn, transaction(conn):
            mapped = conn.execute("SELECT patient_id FROM cm_devices WHERE device_id=?",(device_id,)).fetchone()
            if mapped is None:
                raise HTTPException(403,"Device has no patient mapping")
            patient_id = mapped[0]
            if nonce is not None:
                existing = conn.execute("""SELECT id,received_at,payload_json FROM cm_readings
                    WHERE device_id=? AND boot_nonce=? AND sample_id=?""",(device_id,nonce,sample)).fetchone()
                if existing is not None:
                    if existing["payload_json"] != serialised:
                        raise HTTPException(409,"Sample identity was already used for different data")
                    return {"status":"received","record_id":existing["id"],"patient_id":patient_id,
                            "received_at":existing["received_at"],"duplicate":True,"alert_created":False,"alert_id":None}
            cursor = conn.execute("""INSERT INTO cm_readings(device_id,patient_id,received_at,
                sensor_timestamp,uptime_ms,boot_nonce,sample_id,payload_json) VALUES (?,?,?,?,?,?,?,?)""",
                (device_id,patient_id,received_at,snapshot.get("timestamp"),snapshot.get("uptime_ms"),nonce,sample,serialised))
            reading_id = int(cursor.lastrowid)
            result = alert_engine.process_snapshot(conn,reading_id,device_id,patient_id,snapshot,received_at)
        return {"status":"received","record_id":reading_id,"patient_id":patient_id,"received_at":received_at,
                "duplicate":False,"alert_created":bool(result.get("alert_created",False)),"alert_id":result.get("alert_id")}

    @application.get("/sensor-data")
    def get_readings(request: Request, device_id: str | None = None, limit: int = Query(20,ge=1,le=100)) -> list[dict]:
        credential = authenticate(request,"sensor:read",device_id)
        with connect(target) as conn:
            rows = conn.execute("SELECT * FROM cm_readings WHERE device_id=? ORDER BY id DESC LIMIT ?",(credential.device_id,limit)).fetchall()
        return [{"id":row["id"],"device_id":row["device_id"],"patient_id":row["patient_id"],
                 "received_at":row["received_at"],"data":json.loads(row["payload_json"])} for row in rows]

    def latest_sensor(request: Request, device_id: str | None) -> tuple[sqlite3.Row,dict]:
        credential = authenticate(request,"sensor:read",device_id)
        with connect(target) as conn:
            row = conn.execute("SELECT * FROM cm_readings WHERE device_id=? ORDER BY id DESC LIMIT 1",(credential.device_id,)).fetchone()
        if row is None:
            raise HTTPException(404,"No readings received for this device")
        return row,json.loads(row["payload_json"])

    @application.get("/sensors")
    def get_sensors(request: Request, device_id: str | None = None) -> dict:
        row,payload = latest_sensor(request,device_id)
        return {"status":"ok","device_id":row["device_id"],"received_at":row["received_at"],
                "sensors":{name:payload.get(name) for name in SENSOR_NAMES}}

    @application.get("/sensors/{sensor_name}")
    def get_sensor(sensor_name: str, request: Request, device_id: str | None = None) -> dict:
        row,payload = latest_sensor(request,device_id)
        if sensor_name not in SENSOR_NAMES:
            raise HTTPException(404,"Unknown sensor")
        return {"status":"ok","device_id":row["device_id"],"received_at":row["received_at"],
                "sensor":sensor_name,"data":payload.get(sensor_name)}

    @application.get("/alerts")
    def get_alerts(request: Request, device_id: str | None = None,
                   after_revision: int = Query(0,ge=0,le=2**63-1),limit: int = Query(100,ge=1,le=100)) -> dict:
        credential = authenticate(request,"alerts:read",device_id)
        with connect(target) as conn:
            page = alert_engine.list_alerts(conn,credential.device_id,after_revision,limit)
            identity = server_id(conn)
            capabilities = alert_engine.capabilities(conn)
        return {"status":"ok","device_id":credential.device_id,"server_id":identity,
                **page,"server_time":utc_timestamp(),"mode":"shadow","capabilities":capabilities}

    @application.post("/alerts/{alert_id}/acknowledge")
    async def acknowledge_alert(request: Request, alert_id: int = PathParameter(ge=1,le=2**63-1)) -> dict:
        credential = authenticate(request,"alerts:ack")
        body = await read_object(request)
        if set(body) != {"device_id"} or body["device_id"] != credential.device_id:
            raise HTTPException(403,"Credential is not authorised for this device")
        with connect(target) as conn, transaction(conn):
            try:
                entry = alert_engine.acknowledge(conn,alert_id,credential.device_id,utc_timestamp())
            except (KeyError,LookupError):
                raise HTTPException(404,"Alert not found for this device") from None
            if entry is None:
                raise HTTPException(404,"Alert not found for this device")
            audit(conn,"alert_acknowledged",credential.key_id,credential.device_id,"ok",int(time.time()))
            identity = server_id(conn)
        return {"status":"acknowledged","device_id":credential.device_id,"server_id":identity,"alert":entry}

    return application


app = create_app()
