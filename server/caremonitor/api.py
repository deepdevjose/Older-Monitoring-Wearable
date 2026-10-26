"""Authenticated CareMonitor API. Importing `app` does not touch disk or hardware."""
import asyncio
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timezone
import json
import logging
import secrets
import sqlite3
import time
import uuid

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import __version__
from .alerts import CAPABILITIES, create_alert, evaluate_reading, wear_inference
from .config import Settings
from .db import Database
from .push import iso, process_due_reminders
from .schemas import Consents, Event, LinkRequest, Location, Login, Reading, Register, Reminder
from .security import DUMMY_PASSWORD_HASH, check_password, hash_password, new_token, token_hash

logger = logging.getLogger("caremonitor")
PREFIX = "/api/v1"


def get_database(request: Request) -> Database:
    return request.app.state.db


def user_view(db, row):
    return {"id":row["id"],"role":row["role"],**db.vault.open(row["profile"],f"user:{row['id']}")}


def get_current_user(request: Request, db=Depends(get_database)):
    header=request.headers.get("authorization","")
    if not header.startswith("Bearer ") or not 20<=len(header[7:])<=200:
        raise HTTPException(401,"Authentication required",headers={"WWW-Authenticate":"Bearer"})
    digest=token_hash(header[7:])
    with db.connection() as conn:
        row=conn.execute("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?",
                         (digest,int(time.time()))).fetchone()
    if not row:
        raise HTTPException(401,"Session expired or invalid",headers={"WWW-Authenticate":"Bearer"})
    request.state.token_hash=digest
    return user_view(db,row)


def require_role(user, role):
    if user["role"]!=role:
        raise HTTPException(403,"Role not permitted")


def authorize_patient(conn,user,patient_id,*,caregiver_only=False,elder_only=False):
    if elder_only:
        permitted=user["role"]=="elder" and user["id"]==patient_id
    elif caregiver_only:
        permitted=user["role"]=="caregiver" and bool(conn.execute("SELECT 1 FROM memberships WHERE elder_id=? AND caregiver_id=?",(patient_id,user["id"])).fetchone())
    else:
        permitted=(user["role"]=="elder" and user["id"]==patient_id) or bool(conn.execute("SELECT 1 FROM memberships WHERE elder_id=? AND caregiver_id=?",(patient_id,user["id"])).fetchone())
    if not permitted or not conn.execute("SELECT 1 FROM patients WHERE id=?",(patient_id,)).fetchone():
        raise HTTPException(404,"Patient not found")


def patient_view(conn,db,patient_id):
    row=conn.execute("SELECT u.*,p.device_id FROM patients p JOIN users u ON u.id=p.id WHERE p.id=?",(patient_id,)).fetchone()
    profile=db.vault.open(row["profile"],f"user:{patient_id}")
    return {"id":patient_id,"display_name":profile["display_name"],"device_id":row["device_id"]}


def reading_view(db,row):
    if row is None:
        return None
    return {**db.vault.open(row["envelope"],f"reading:{row['id']}"),"record_id":row["id"],"received_at":iso(row["received_at"])}


def alert_view(conn,db,row,user_id):
    ack=conn.execute("SELECT acknowledged_at FROM alert_acknowledgements WHERE alert_id=? AND user_id=?",(row["id"],user_id)).fetchone()
    return {"id":row["id"],"patient_id":row["elder_id"],"type":row["type"],"created_at":iso(row["created_at"]),
            "source":row["source"],**db.vault.open(row["detail"],f"alert:{row['id']}"),"acknowledged":bool(ack),
            "acknowledged_at":iso(ack[0]) if ack else None,
            "episode_status":"recovered" if row["recovered_at"] else "open",
            "recovered_at":iso(row["recovered_at"]) if row["recovered_at"] else None}


def reminder_view(db,row):
    return {"id":row["id"],"patient_id":row["elder_id"],**db.vault.open(row["detail"],f"reminder:{row['id']}"),
            "due_at":iso(row["next_due_at"]),"repeat_hours":row["repeat_hours"],"active":bool(row["active"]),
            "last_due_at":iso(row["last_due_at"]) if row["last_due_at"] else None,
            "taken_at":iso(row["taken_at"]) if row["taken_at"] else None,
            "cancelled_at":iso(row["cancelled_at"]) if row["cancelled_at"] else None}


def inbox_view(db,user,after,limit=100):
    with db.connection() as conn:
        rows=conn.execute("SELECT n.* FROM notifications n WHERE n.user_id=? AND n.id>? AND (n.elder_id=? OR EXISTS(SELECT 1 FROM memberships m WHERE m.elder_id=n.elder_id AND m.caregiver_id=?)) ORDER BY n.id LIMIT ?",(user["id"],after,user["id"],user["id"],limit)).fetchall()
        items=[{"id":row["id"],"type":row["type"],"created_at":iso(row["created_at"]),"data":db.vault.open(row["data"],f"notification:{row['id']}"),"acknowledged":row["acknowledged_at"] is not None} for row in rows]
        return {"items":items,"cursor":rows[-1]["id"] if rows else after}


def rate_guard(db, request, scope, *, account=None, limit=10, period=600, global_limit=None):
    # Forwarded headers are deliberately ignored; only a configured trusted proxy may rewrite client IP.
    ip=request.client.host if request.client else "unknown"
    permitted=db.rate_limit(scope+":ip",db.vault.lookup("ip",ip),limit*4,period)
    if account:
        permitted=db.rate_limit(scope+":account",db.vault.lookup("account",account),limit,period) and permitted
    if global_limit:
        permitted=db.rate_limit(scope+":global","all",global_limit,period) and permitted
    if not permitted:
        raise HTTPException(429,"Too many attempts. Try later.",headers={"Retry-After":str(period)})


def authenticated_rate(db,user,scope,limit,period=60):
    if not db.rate_limit(scope+":user",user["id"],limit,period):
        raise HTTPException(429,"Too many requests. Try later.",headers={"Retry-After":str(period)})


class RequestSecurity:
    """Bound bodies before JSON parsing and apply headers even to framework errors."""
    def __init__(self,app,settings):
        self.app=app
        self.settings=settings

    async def __call__(self,scope,receive,send):
        if scope["type"]!="http":
            return await self.app(scope,receive,send)
        headers={k.decode().lower():v.decode() for k,v in scope.get("headers",[])}

        async def secured_send(message):
            if message["type"]=="http.response.start":
                message.setdefault("headers",[]).extend([
                    (b"cache-control",b"no-store"),(b"pragma",b"no-cache"),
                    (b"x-content-type-options",b"nosniff"),(b"x-frame-options",b"DENY"),
                    (b"referrer-policy",b"no-referrer"),
                    (b"content-security-policy",b"default-src 'none'; frame-ancestors 'none'"),
                    (b"permissions-policy",b"camera=(), microphone=(), geolocation=()"),
                ])
                if scope.get("scheme")=="https":
                    message["headers"].append((b"strict-transport-security",b"max-age=31536000"))
            await send(message)

        async def reject(status,detail):
            response=JSONResponse({"detail":detail},status_code=status)
            await response(scope,receive,secured_send)

        if self.settings.enforce_https and scope.get("scheme")!="https":
            return await reject(400,"HTTPS is required")
        length=headers.get("content-length")
        if length:
            try:
                if int(length)<0 or int(length)>self.settings.max_body_bytes:
                    return await reject(413,"Request body too large")
            except ValueError:
                return await reject(400,"Invalid content length")
        body=bytearray()
        while True:
            message=await receive()
            if message["type"]=="http.disconnect":
                return
            body.extend(message.get("body",b""))
            if len(body)>self.settings.max_body_bytes:
                return await reject(413,"Request body too large")
            if not message.get("more_body",False):
                break
        if body:
            if headers.get("content-encoding","identity")!="identity":
                return await reject(415,"Compressed request bodies are not supported")
            if headers.get("content-type","").split(";",1)[0].strip().lower()!="application/json":
                return await reject(415,"application/json is required")
            def no_duplicates(pairs):
                obj={}
                for key,value in pairs:
                    if key in obj:
                        raise ValueError("Duplicate key")
                    obj[key]=value
                return obj
            try:
                json.loads(body,object_pairs_hook=no_duplicates,parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite number")))
            except (ValueError,UnicodeError,RecursionError):
                return await reject(400,"Invalid JSON")
        delivered=False
        async def replay():
            nonlocal delivered
            if not delivered:
                delivered=True
                return {"type":"http.request","body":bytes(body),"more_body":False}
            return await receive()
        await self.app(scope,replay,secured_send)


def create_app(settings=None):
    settings=settings or Settings()

    @asynccontextmanager
    async def lifespan(application):
        db=Database(settings)
        db.initialize()
        application.state.db=db
        # CLI and tests may invoke this same helper without starting a second scheduler.
        application.state.process_due_reminders=lambda now=None: process_due_reminders(db,now)
        async def scheduler():
            while True:
                try:
                    await asyncio.to_thread(process_due_reminders,db)
                except Exception:
                    # Deliberately omit payloads, profile data, tokens and database paths.
                    logger.error("Reminder scheduler cycle failed")
                await asyncio.sleep(settings.scheduler_interval_seconds)
        task=asyncio.create_task(scheduler())
        try:
            yield
        finally:
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    application=FastAPI(title="CareMonitor API",version=__version__,lifespan=lifespan,
                        docs_url=None,redoc_url=None,
                        openapi_url="/openapi.json" if settings.expose_docs else None)
    if settings.expose_docs:
        @application.get("/docs",include_in_schema=False)
        def local_contract():
            # No Swagger/Redoc CDN: even optional documentation stays self-hosted.
            from html import escape
            from fastapi.responses import HTMLResponse
            schema=escape(json.dumps(application.openapi(),ensure_ascii=False,indent=2))
            return HTMLResponse("<!doctype html><html lang=es><meta charset=utf-8>"
                "<title>CareMonitor API local</title><h1>Contrato API local</h1>"
                "<p>Referencia de solo lectura. Las operaciones requieren sesiones autorizadas.</p><pre>"+schema+"</pre></html>")
    application.state.settings=settings
    application.add_middleware(RequestSecurity,settings=settings)

    @application.exception_handler(RequestValidationError)
    async def sanitized_validation(request,exc):
        # FastAPI normally includes rejected input (including passwords) in errors.
        return JSONResponse(status_code=422,content={"detail":[{"loc":list(e["loc"]),"type":e["type"],"msg":"Invalid or unsupported field"} for e in exc.errors()]})

    @application.exception_handler(sqlite3.Error)
    async def database_error(request,exc):
        logger.error("Database operation failed")
        return JSONResponse(status_code=503,content={"detail":"Storage temporarily unavailable"})

    @application.get("/health")
    @application.get(PREFIX+"/health")
    def health():
        return {"status":"ok","mode":"academic_demo","version":__version__}

    def issue_session(conn,db,user):
        token=new_token()
        now=int(time.time())
        expires=now+settings.session_hours*3600
        conn.execute("INSERT INTO sessions(token_hash,user_id,expires_at,created_at) VALUES (?,?,?,?)",(token_hash(token),user["id"],expires,now))
        return {"access_token":token,"expires_at":iso(expires),"user":user}

    @application.post(PREFIX+"/auth/register",status_code=201)
    def register(data:Register,request:Request,db=Depends(get_database)):
        rate_guard(db,request,"register",account=data.email,limit=5,period=3600,global_limit=100)
        lookup=db.vault.lookup("email",data.email)
        with db.connection() as conn:
            if conn.execute("SELECT 1 FROM users WHERE email_lookup=?",(lookup,)).fetchone():
                raise HTTPException(409,"Account cannot be registered")
        password=hash_password(data.password)
        user_id=uuid.uuid4().hex
        now=int(time.time())
        profile={"email":data.email,"display_name":data.display_name}
        with db.transaction() as conn:
            try:
                conn.execute("INSERT INTO users VALUES (?,?,?,?,?,?,?)",(user_id,lookup,db.vault.seal(profile,f"user:{user_id}"),password,data.role,now,now))
            except sqlite3.IntegrityError:
                raise HTTPException(409,"Account cannot be registered")
            if data.role=="elder":
                conn.execute("INSERT INTO patients(id) VALUES (?)",(user_id,))
                conn.execute("INSERT INTO consents(elder_id,updated_at) VALUES (?,?)",(user_id,now))
            db.audit(conn,user_id,"account_registered")
            return issue_session(conn,db,{"id":user_id,"role":data.role,**profile})

    @application.post(PREFIX+"/auth/login")
    def login(data:Login,request:Request,db=Depends(get_database)):
        rate_guard(db,request,"login",account=data.email,limit=8,period=900,global_limit=250)
        with db.connection() as conn:
            row=conn.execute("SELECT * FROM users WHERE email_lookup=?",(db.vault.lookup("email",data.email),)).fetchone()
        if not check_password(row["password_hash"] if row else DUMMY_PASSWORD_HASH,data.password):
            raise HTTPException(401,"Invalid credentials")
        with db.transaction() as conn:
            db.audit(conn,row["id"],"session_created")
            return issue_session(conn,db,user_view(db,row))

    @application.get(PREFIX+"/me")
    def me(user=Depends(get_current_user)):
        return user

    @application.post(PREFIX+"/auth/logout")
    def logout(request:Request,user=Depends(get_current_user),db=Depends(get_database)):
        with db.transaction() as conn:
            conn.execute("UPDATE sessions SET revoked_at=? WHERE token_hash=?",(int(time.time()),request.state.token_hash))
            db.audit(conn,user["id"],"session_revoked")
        return {"status":"logged_out"}

    @application.get(PREFIX+"/patients")
    def patients(user=Depends(get_current_user),db=Depends(get_database)):
        with db.transaction() as conn:
            if user["role"]=="elder":
                ids=[user["id"]]
            else:
                ids=[r[0] for r in conn.execute("SELECT elder_id FROM memberships WHERE caregiver_id=? ORDER BY approved_at",(user["id"],))]
            return {"patients":[patient_view(conn,db,pid) for pid in ids]}

    @application.get(PREFIX+"/patients/{patient_id}/overview")
    def overview(patient_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        now=int(time.time())
        with db.transaction() as conn:
            authorize_patient(conn,user,patient_id)
            latest=conn.execute("SELECT * FROM readings WHERE elder_id=? ORDER BY captured_at DESC,id DESC LIMIT 1",(patient_id,)).fetchone()
            reading=reading_view(db,latest)
            age=max(0,now-latest["captured_at"]) if latest else None
            rows=conn.execute("SELECT * FROM alerts WHERE elder_id=? ORDER BY created_at DESC,id DESC LIMIT 50",(patient_id,)).fetchall()
            consent=conn.execute("SELECT location FROM consents WHERE elder_id=?",(patient_id,)).fetchone()
            loc=conn.execute("SELECT * FROM locations WHERE elder_id=? AND expires_at>? ORDER BY recorded_at DESC,id DESC LIMIT 1",(patient_id,now)).fetchone() if consent and consent[0] else None
            location={**db.vault.open(loc["detail"],f"location:{loc['id']}"),"expires_at":iso(loc["expires_at"])} if loc else None
            db.audit(conn,user["id"],"overview_viewed",patient_id)
            return {"patient":patient_view(conn,db,patient_id),"reading":reading,
                    "alerts":[alert_view(conn,db,row,user["id"]) for row in rows],"capabilities":CAPABILITIES,
                    "location":location,"freshness":{"state":"no_data" if age is None else "fresh" if age<=10 else "stale","age_seconds":age},
                    "wear_inference":wear_inference(reading["payload"]) if reading and age is not None and age<=10 else {"state":"unknown","validated":False,"reason":"No fresh sensor evidence"}}

    @application.post(PREFIX+"/readings",status_code=201)
    def readings_post(data:Reading,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        authenticated_rate(db,user,"readings",180)
        now=int(time.time())
        captured=int(data.captured_at.timestamp())
        if captured>now+30 or captured<now-30*86400:
            raise HTTPException(422,"Capture time outside supported window")
        envelope=data.model_dump(mode="json")
        digest=db.vault.lookup("reading",json.dumps(envelope,sort_keys=True,separators=(",",":")))
        with db.transaction() as conn:
            authorize_patient(conn,user,user["id"],elder_only=True)
            device=conn.execute("SELECT device_id FROM patients WHERE id=?",(user["id"],)).fetchone()[0]
            if data.payload["device_id"]!=device:
                raise HTTPException(403,"Device is not assigned to this account")
            existing=conn.execute("SELECT id,digest FROM readings WHERE elder_id=? AND sample_id=?",(user["id"],data.sample_id)).fetchone()
            if existing:
                if existing["digest"]!=digest:
                    raise HTTPException(409,"sample_id already refers to a different reading")
                return {"status":"received","record_id":existing["id"],"duplicate":True,"alert_created":False}
            cur=conn.execute("INSERT INTO readings(elder_id,sample_id,captured_at,received_at,source,envelope,digest) VALUES (?,?,?,?,?,?,?)",
                             (user["id"],data.sample_id,captured,now,data.source,"pending",digest))
            record_id=cur.lastrowid
            conn.execute("UPDATE readings SET envelope=? WHERE id=?",(db.vault.seal(envelope,f"reading:{record_id}"),record_id))
            created=evaluate_reading(conn,db,user["id"],data,now=now)
            return {"status":"received","record_id":record_id,"duplicate":False,"alert_created":bool(created)}

    @application.get(PREFIX+"/patients/{patient_id}/readings")
    def readings_get(patient_id:str,limit:int=Query(50,ge=1,le=500),user=Depends(get_current_user),db=Depends(get_database)):
        with db.transaction() as conn:
            authorize_patient(conn,user,patient_id)
            rows=conn.execute("SELECT * FROM readings WHERE elder_id=? ORDER BY captured_at DESC,id DESC LIMIT ?",(patient_id,limit)).fetchall()
            db.audit(conn,user["id"],"readings_viewed",patient_id)
            return {"readings":[reading_view(db,row) for row in rows]}

    @application.get(PREFIX+"/patients/{patient_id}/alerts")
    def alerts_get(patient_id:str,limit:int=Query(50,ge=1,le=500),user=Depends(get_current_user),db=Depends(get_database)):
        with db.transaction() as conn:
            authorize_patient(conn,user,patient_id)
            rows=conn.execute("SELECT * FROM alerts WHERE elder_id=? ORDER BY created_at DESC,id DESC LIMIT ?",(patient_id,limit)).fetchall()
            return {"alerts":[alert_view(conn,db,row,user["id"]) for row in rows]}

    @application.post(PREFIX+"/alerts/{alert_id}/acknowledge")
    def alert_ack(alert_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"caregiver")
        with db.transaction() as conn:
            row=conn.execute("SELECT * FROM alerts WHERE id=?",(alert_id,)).fetchone()
            if not row:
                raise HTTPException(404,"Alert not found")
            authorize_patient(conn,user,row["elder_id"],caregiver_only=True)
            conn.execute("INSERT OR IGNORE INTO alert_acknowledgements VALUES (?,?,?)",(alert_id,user["id"],int(time.time())))
            db.audit(conn,user["id"],"alert_acknowledged",alert_id)
        return {"status":"acknowledged","patient_resolved":False}

    @application.post(PREFIX+"/invitations",status_code=201)
    def invitations(request:Request,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        rate_guard(db,request,"invitation",account=user["id"],limit=5,period=3600,global_limit=100)
        now=int(time.time())
        with db.transaction() as conn:
            authorize_patient(conn,user,user["id"],elder_only=True)
            conn.execute("UPDATE invitations SET revoked_at=? WHERE elder_id=? AND used_at IS NULL AND revoked_at IS NULL",(now,user["id"]))
            for _ in range(100):
                code=f"{secrets.randbelow(10000):04d}"
                lookup=db.vault.lookup("invitation",code)
                if not conn.execute("SELECT 1 FROM invitations WHERE code_lookup=? AND expires_at>? AND used_at IS NULL AND revoked_at IS NULL",(lookup,now)).fetchone():
                    break
            else:
                raise HTTPException(503,"Invitation capacity temporarily unavailable")
            invitation_id=uuid.uuid4().hex
            conn.execute("INSERT INTO invitations(id,elder_id,code_lookup,expires_at,created_at) VALUES (?,?,?,?,?)",(invitation_id,user["id"],lookup,now+600,now))
            db.audit(conn,user["id"],"invitation_created",invitation_id)
        return {"code":code,"expires_at":iso(now+600)}

    @application.post(PREFIX+"/link-requests",status_code=201)
    def request_link(data:LinkRequest,request:Request,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"caregiver")
        rate_guard(db,request,"link",account=user["id"],limit=5,period=600,global_limit=200)
        now=int(time.time())
        with db.transaction() as conn:
            invite=conn.execute("SELECT * FROM invitations WHERE code_lookup=? AND expires_at>? AND used_at IS NULL AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1",(db.vault.lookup("invitation",data.code),now)).fetchone()
            if not invite:
                raise HTTPException(400,"Invitation unavailable")
            if conn.execute("SELECT 1 FROM memberships WHERE elder_id=? AND caregiver_id=?",(invite["elder_id"],user["id"])).fetchone():
                raise HTTPException(409,"Already linked")
            existing=conn.execute("SELECT id FROM link_requests WHERE invitation_id=? AND caregiver_id=?",(invite["id"],user["id"])).fetchone()
            request_id=existing[0] if existing else uuid.uuid4().hex
            if not existing:
                conn.execute("INSERT INTO link_requests VALUES (?,?,?,?,?,?)",(request_id,invite["id"],invite["elder_id"],user["id"],"pending",now))
                db.audit(conn,user["id"],"link_requested",request_id)
        # No patient identity or data is disclosed until the elder explicitly approves.
        return {"id":request_id,"status":"pending"}

    @application.get(PREFIX+"/link-requests")
    def link_requests(user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        now=int(time.time())
        with db.transaction() as conn:
            rows=conn.execute("SELECT r.*,u.profile FROM link_requests r JOIN invitations i ON i.id=r.invitation_id JOIN users u ON u.id=r.caregiver_id WHERE r.elder_id=? AND r.status='pending' AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at>? ORDER BY r.created_at",(user["id"],now)).fetchall()
            return {"requests":[{"id":row["id"],"status":"pending","caregiver":{"id":row["caregiver_id"],**db.vault.open(row["profile"],f"user:{row['caregiver_id']}")},"created_at":iso(row["created_at"])} for row in rows]}

    @application.post(PREFIX+"/link-requests/{request_id}/approve")
    def approve_link(request_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        now=int(time.time())
        with db.transaction() as conn:
            row=conn.execute("SELECT r.*,i.expires_at,i.used_at,i.revoked_at FROM link_requests r JOIN invitations i ON i.id=r.invitation_id WHERE r.id=? AND r.elder_id=?",(request_id,user["id"])).fetchone()
            if not row:
                raise HTTPException(404,"Request not found")
            if row["status"]!="pending" or row["expires_at"]<=now or row["used_at"] or row["revoked_at"]:
                raise HTTPException(409,"Invitation no longer available")
            conn.execute("UPDATE invitations SET used_at=? WHERE id=?",(now,row["invitation_id"]))
            conn.execute("UPDATE link_requests SET status='expired' WHERE invitation_id=? AND status='pending'",(row["invitation_id"],))
            conn.execute("UPDATE link_requests SET status='approved' WHERE id=?",(request_id,))
            conn.execute("INSERT OR IGNORE INTO memberships VALUES (?,?,?)",(user["id"],row["caregiver_id"],now))
            db.audit(conn,user["id"],"caregiver_approved",row["caregiver_id"])
        return {"status":"approved"}

    @application.get(PREFIX+"/caregivers")
    def caregivers(user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        with db.transaction() as conn:
            rows=conn.execute("SELECT u.* FROM memberships m JOIN users u ON u.id=m.caregiver_id WHERE m.elder_id=? ORDER BY approved_at",(user["id"],)).fetchall()
            return {"caregivers":[{"id":row["id"],"display_name":db.vault.open(row["profile"],f"user:{row['id']}")["display_name"]} for row in rows]}

    @application.delete(PREFIX+"/caregivers/{caregiver_id}")
    def revoke_caregiver(caregiver_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        with db.transaction() as conn:
            if not conn.execute("DELETE FROM memberships WHERE elder_id=? AND caregiver_id=?",(user["id"],caregiver_id)).rowcount:
                raise HTTPException(404,"Caregiver not found")
            # Outstanding invitations and requests must not silently restore revoked access.
            now=int(time.time())
            conn.execute("UPDATE invitations SET revoked_at=? WHERE elder_id=? AND used_at IS NULL AND revoked_at IS NULL",(now,user["id"]))
            conn.execute("UPDATE link_requests SET status='expired' WHERE elder_id=? AND caregiver_id=? AND status='pending'",(user["id"],caregiver_id))
            authored=conn.execute("SELECT id FROM reminders WHERE elder_id=? AND creator_id=? AND cancelled_at IS NULL",(user["id"],caregiver_id)).fetchall()
            for reminder in authored:
                conn.execute("UPDATE reminders SET active=0,cancelled_at=? WHERE id=?",(now,reminder["id"]))
                conn.execute("DELETE FROM notifications WHERE elder_id=? AND acknowledged_at IS NULL AND occurrence_key LIKE ?",(user["id"],f"reminder:{reminder['id']}:%"))
            conn.execute("DELETE FROM notifications WHERE elder_id=? AND user_id=?",(user["id"],caregiver_id))
            db.audit(conn,user["id"],"caregiver_revoked",caregiver_id)
        return {"status":"revoked"}

    @application.get(PREFIX+"/notifications")
    def notifications(after:int=Query(0,ge=0),limit:int=Query(100,ge=1,le=200),user=Depends(get_current_user),db=Depends(get_database)):
        return inbox_view(db,user,after,limit)

    active_polls={}

    @application.get(PREFIX+"/notifications/wait")
    async def notifications_wait(request:Request,after:int=Query(0,ge=0),timeout:int=Query(20,ge=1,le=30),
                                 user=Depends(get_current_user),db=Depends(get_database)):
        # This is an authenticated private long poll, not a shared broadcast channel.
        # Bound concurrent waits without charging normal foreground delivery a request rate.
        uid=user["id"]
        if active_polls.get(uid,0)>=5 or sum(active_polls.values())>=100:
            raise HTTPException(429,"Too many active notification connections",headers={"Retry-After":"5"})
        active_polls[uid]=active_polls.get(uid,0)+1
        deadline=asyncio.get_running_loop().time()+timeout
        try:
            while True:
                # Dependency evaluation at connection start alone is insufficient: sessions
                # can expire/logout and caregiver permissions can be revoked while waiting.
                refreshed=await asyncio.to_thread(get_current_user,request,db)
                result=await asyncio.to_thread(inbox_view,db,refreshed,after,100)
                remaining=deadline-asyncio.get_running_loop().time()
                if result["items"] or remaining<=0:
                    return result
                await asyncio.sleep(min(1.0,remaining))
        finally:
            active_polls[uid]-=1
            if active_polls[uid]==0:
                del active_polls[uid]

    @application.post(PREFIX+"/notifications/{notification_id}/ack")
    def notification_ack(notification_id:int,user=Depends(get_current_user),db=Depends(get_database)):
        with db.transaction() as conn:
            row=conn.execute("SELECT * FROM notifications WHERE id=? AND user_id=?",(notification_id,user["id"])).fetchone()
            if not row:
                raise HTTPException(404,"Notification not found")
            authorize_patient(conn,user,row["elder_id"])
            conn.execute("UPDATE notifications SET acknowledged_at=COALESCE(acknowledged_at,?) WHERE id=?",(int(time.time()),notification_id))
        return {"status":"delivered"}

    @application.post(PREFIX+"/patients/{patient_id}/reminders",status_code=201)
    def reminder_create(patient_id:str,data:Reminder,user=Depends(get_current_user),db=Depends(get_database)):
        authenticated_rate(db,user,"reminder_create",20,3600)
        now=int(time.time())
        due=int(data.due_at.timestamp())
        if not now-86400<=due<=now+366*86400:
            raise HTTPException(422,"Reminder date outside supported window")
        with db.transaction() as conn:
            authorize_patient(conn,user,patient_id,caregiver_only=True)
            if conn.execute("SELECT COUNT(*) FROM reminders WHERE elder_id=? AND active=1",(patient_id,)).fetchone()[0]>=50:
                raise HTTPException(409,"Cancel an existing reminder before adding more")
            reminder_id=uuid.uuid4().hex
            detail={"title":data.title,"dose":data.dose,"instructions":data.instructions}
            conn.execute("INSERT INTO reminders(id,elder_id,creator_id,detail,next_due_at,repeat_hours,created_at) VALUES (?,?,?,?,?,?,?)",(reminder_id,patient_id,user["id"],db.vault.seal(detail,f"reminder:{reminder_id}"),due,data.repeat_hours,now))
            db.audit(conn,user["id"],"reminder_created",reminder_id)
            row=conn.execute("SELECT * FROM reminders WHERE id=?",(reminder_id,)).fetchone()
            return reminder_view(db,row)

    @application.get(PREFIX+"/patients/{patient_id}/reminders")
    def reminders(patient_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        with db.transaction() as conn:
            authorize_patient(conn,user,patient_id)
            rows=conn.execute("SELECT * FROM reminders WHERE elder_id=? ORDER BY active DESC,CASE WHEN active=1 THEN next_due_at ELSE -created_at END LIMIT 200",(patient_id,)).fetchall()
            return {"reminders":[reminder_view(db,row) for row in rows]}

    @application.post(PREFIX+"/reminders/{reminder_id}/taken")
    def reminder_taken(reminder_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        now=int(time.time())
        with db.transaction() as conn:
            row=conn.execute("SELECT * FROM reminders WHERE id=? AND elder_id=?",(reminder_id,user["id"])).fetchone()
            if not row:
                raise HTTPException(404,"Reminder not found")
            if row["cancelled_at"]:
                raise HTTPException(409,"Reminder was cancelled")
            occurrence=conn.execute("SELECT * FROM reminder_occurrences WHERE reminder_id=? ORDER BY due_at DESC LIMIT 1",(reminder_id,)).fetchone()
            if not occurrence:
                raise HTTPException(409,"Reminder is not due")
            conn.execute("UPDATE reminder_occurrences SET taken_at=COALESCE(taken_at,?) WHERE reminder_id=? AND due_at=?",(now,reminder_id,occurrence["due_at"]))
            conn.execute("UPDATE reminders SET taken_at=COALESCE(taken_at,?) WHERE id=?",(now,reminder_id))
            db.audit(conn,user["id"],"reminder_marked_taken",reminder_id)
        return {"status":"recorded","message":"Self-reported confirmation; no medication ingestion is verified."}

    @application.delete(PREFIX+"/reminders/{reminder_id}")
    def reminder_cancel(reminder_id:str,user=Depends(get_current_user),db=Depends(get_database)):
        authenticated_rate(db,user,"reminder_cancel",60)
        now=int(time.time())
        with db.transaction() as conn:
            row=conn.execute("SELECT * FROM reminders WHERE id=?",(reminder_id,)).fetchone()
            if not row:
                raise HTTPException(404,"Reminder not found")
            authorize_patient(conn,user,row["elder_id"])
            conn.execute("UPDATE reminders SET active=0,cancelled_at=COALESCE(cancelled_at,?) WHERE id=?",(now,reminder_id))
            conn.execute("DELETE FROM notifications WHERE elder_id=? AND acknowledged_at IS NULL AND occurrence_key LIKE ?",(row["elder_id"],f"reminder:{reminder_id}:%"))
            db.audit(conn,user["id"],"reminder_cancelled",reminder_id)
        return {"status":"cancelled"}

    @application.get(PREFIX+"/consents")
    def consent_get(user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        with db.connection() as conn:
            row=conn.execute("SELECT * FROM consents WHERE elder_id=?",(user["id"],)).fetchone()
            return {"location":bool(row["location"]),"audio":bool(row["audio"]),"updated_at":iso(row["updated_at"])}

    @application.post(PREFIX+"/consents")
    def consent_set(data:Consents,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        authenticated_rate(db,user,"consent",30)
        granted_now=time.time()
        now=int(granted_now)
        with db.transaction() as conn:
            prior=conn.execute("SELECT location,location_granted_at FROM consents WHERE elder_id=?",(user["id"],)).fetchone()
            grant=(prior["location_granted_at"] if prior["location"] and prior["location_granted_at"] else granted_now) if data.location else None
            conn.execute("UPDATE consents SET location=?,audio=?,updated_at=?,location_granted_at=? WHERE elder_id=?",(int(data.location),int(data.audio),now,grant,user["id"]))
            if not data.location:
                conn.execute("DELETE FROM locations WHERE elder_id=?",(user["id"],))
            db.audit(conn,user["id"],"consent_updated")
        return {"location":data.location,"audio":data.audio,"updated_at":iso(now)}

    @application.post(PREFIX+"/location",status_code=201)
    def location_post(data:Location,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        authenticated_rate(db,user,"location",120)
        now=int(time.time())
        recorded=int(data.recorded_at.timestamp())
        if not data.outside_home:
            raise HTTPException(422,"Home coordinates are not collected")
        if not now-300<=recorded<=now+30:
            raise HTTPException(422,"Location must be recent")
        with db.transaction() as conn:
            consent=conn.execute("SELECT location,location_granted_at FROM consents WHERE elder_id=?",(user["id"],)).fetchone()
            if not consent or not consent[0]:
                raise HTTPException(403,"Location consent required")
            if consent["location_granted_at"] is None or data.recorded_at.timestamp()<consent["location_granted_at"]:
                raise HTTPException(422,"Location predates current consent")
            cur=conn.execute("INSERT INTO locations(elder_id,recorded_at,expires_at,detail) VALUES (?,?,?,?)",(user["id"],recorded,recorded+86400,"pending"))
            conn.execute("UPDATE locations SET detail=? WHERE id=?",(db.vault.seal(data.model_dump(mode="json"),f"location:{cur.lastrowid}"),cur.lastrowid))
            conn.execute("DELETE FROM locations WHERE expires_at<=?",(now,))
        return {"status":"received","expires_at":iso(recorded+86400)}

    @application.post(PREFIX+"/events",status_code=201)
    def event_post(data:Event,user=Depends(get_current_user),db=Depends(get_database)):
        require_role(user,"elder")
        authenticated_rate(db,user,"events",10)
        now=int(time.time())
        occurred=int(data.occurred_at.timestamp())
        if not now-120<=occurred<=now+30:
            raise HTTPException(422,"Event must be recent")
        if data.type=="sos" and not data.confirmed:
            raise HTTPException(422,"SOS requires explicit human confirmation")
        event_id=uuid.uuid4().hex
        with db.transaction() as conn:
            if data.type=="audio_impact":
                consent=conn.execute("SELECT audio FROM consents WHERE elder_id=?",(user["id"],)).fetchone()
                if not consent or not consent[0]:
                    raise HTTPException(403,"Audio consent required")
            conn.execute("INSERT INTO events VALUES (?,?,?,?,?,?)",(event_id,user["id"],data.type,occurred,now,db.vault.seal(data.model_dump(mode="json"),f"event:{event_id}")))
            alert_id=None
            if data.type=="sos":
                alert_id=create_alert(conn,db,user["id"],"sos","Solicitud de ayuda","El usuario confirmó una solicitud de ayuda. La entrega depende de conectividad; no contacta servicios de emergencia.",data.source,now=now,severity="urgent")
            elif data.source=="synthetic":
                alert_id=create_alert(conn,db,user["id"],data.type,"Evento sintético para revisión","Posible incidente simulado. Solicitar confirmación humana; no se ha validado un detector de accidentes.","synthetic",now=now)
            db.audit(conn,user["id"],"event_received",event_id)
        return {"status":"received","event_id":event_id,"alert_created":bool(alert_id)}

    from .routes_ml import build_router
    application.include_router(build_router(get_current_user,get_database))
    return application


app=create_app()
