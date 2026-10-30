"""Persisted synthetic model exercises, isolated from device readings and care alerts."""
import threading
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query

from .push import iso
from .routes_ml import ClassificationRequest

DATASET_SHA256 = "35240b10eb5a1207d6404e1af06359706afb6796cca089a4e9c3d8b9613a7adb"
# First matching data rows in the repository CSV (header excluded). No human data.
EXAMPLES = [
    {"label": "Normal", "csv_row": 16, "features": {"heart_rate_bpm": 68.0, "spo2_percent": 97.0, "body_temp_c": 36.5, "sample_age_seconds": 0.0}},
    {"label": "Warning", "csv_row": 1, "features": {"heart_rate_bpm": 98.0, "spo2_percent": 96.0, "body_temp_c": 38.1, "sample_age_seconds": 0.0}},
    {"label": "Critical", "csv_row": 2, "features": {"heart_rate_bpm": 105.0, "spo2_percent": 97.0, "body_temp_c": 37.6, "sample_age_seconds": 0.0}},
]


class LearningReading(ClassificationRequest):
    sample_id: uuid.UUID


def build_learning_router(current_user, get_database, authorize_patient):
    router = APIRouter(prefix="/api/v1", tags=["Synthetic learning records"])
    lock = threading.Lock()
    slots = threading.BoundedSemaphore(2)
    cached = []

    def model():
        with lock:
            if not cached:
                try:
                    from .ml import ModelService
                    cached.append(ModelService())
                except (ImportError, OSError, ValueError):
                    raise HTTPException(503, "The local synthetic model is unavailable") from None
            return cached[0]

    def view(db, row):
        return {"id": row["id"], "created_at": iso(row["created_at"]),
                **db.vault.open(row["detail"], f"learning:{row['id']}")}

    @router.get("/patients/{patient_id}/learning/readings")
    def history(patient_id: str, limit: int = Query(30, ge=1, le=100),
                user=Depends(current_user), db=Depends(get_database)):
        if not db.rate_limit("learning-read:user", user["id"], 90, 60):
            raise HTTPException(429, "Wait before refreshing learning history")
        with db.connection() as conn:
            authorize_patient(conn, user, patient_id)
            rows = conn.execute("SELECT * FROM learning_records WHERE elder_id=? AND created_at>=? ORDER BY created_at DESC,rowid DESC LIMIT ?",
                                (patient_id, int(time.time())-30*86400, limit)).fetchall()
            items = [view(db, row) for row in rows]
        service = model()
        return {"patient_id": patient_id, "source": "synthetic", "educational_only": True,
                "can_add": user["role"] == "elder", "items": items,
                "model_id": service.metadata["model_id"],
                "selected_model": service.metadata["selected_model"],
                "limitations": service.metadata["limitations"],
                "feature_ranges": service.metadata["feature_ranges"],
                "dataset_sha256": service.metadata["dataset"]["sha256"],
                "examples": EXAMPLES if service.metadata["dataset"]["sha256"] == DATASET_SHA256 else []}

    @router.post("/patients/{patient_id}/learning/readings", status_code=201)
    def classify(patient_id: str, body: LearningReading,
                 user=Depends(current_user), db=Depends(get_database)):
        sample_id = str(body.sample_id)
        features = body.features.model_dump()
        with db.connection() as conn:
            authorize_patient(conn, user, patient_id, elder_only=True)
            existing = conn.execute("SELECT * FROM learning_records WHERE elder_id=? AND sample_id=?",
                                    (patient_id, sample_id)).fetchone()
            if existing:
                if existing["created_at"] < int(time.time())-30*86400:
                    raise HTTPException(409, "This exercise identifier has expired; use a new identifier")
                previous = view(db, existing)
                if previous["features"] != features:
                    raise HTTPException(409, "This sample identifier already belongs to another exercise")
                return previous
        if not db.rate_limit("learning-write:user", user["id"], 30, 60):
            raise HTTPException(429, "Wait before classifying another synthetic sample")
        if not slots.acquire(blocking=False):
            raise HTTPException(503, "The local model is busy; try again later")
        try:
            prediction = model().predict(features, source=body.source)
        except ValueError:
            raise HTTPException(422, "Values are outside the synthetic model contract") from None
        finally:
            slots.release()
        record_id = uuid.uuid4().hex
        now = int(time.time())
        detail = {"sample_id": sample_id, "features": features, **prediction}
        with db.transaction() as conn:
            # Consent may have changed while inference ran. Check again before writing.
            authorize_patient(conn, user, patient_id, elder_only=True)
            existing = conn.execute("SELECT * FROM learning_records WHERE elder_id=? AND sample_id=?",
                                    (patient_id, sample_id)).fetchone()
            if existing:
                if existing["created_at"] < now-30*86400:
                    raise HTTPException(409, "This exercise identifier has expired; use a new identifier")
                previous = view(db, existing)
                if previous["features"] != features:
                    raise HTTPException(409, "This sample identifier already belongs to another exercise")
                return previous
            conn.execute("INSERT INTO learning_records VALUES (?,?,?,?,?)",
                         (record_id, patient_id, sample_id, now, db.vault.seal(detail, f"learning:{record_id}")))
            db.audit(conn, user["id"], "synthetic_learning_classified", record_id)
        return {"id": record_id, "created_at": iso(now), **detail}

    return router
