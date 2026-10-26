"""Authenticated, bounded synthetic-only model demonstration."""
import threading
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field


class Features(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    heart_rate_bpm: float = Field(strict=True)
    spo2_percent: float = Field(strict=True)
    body_temp_c: float = Field(strict=True)
    sample_age_seconds: float = Field(strict=True, ge=0, le=10)


class ClassificationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source: Literal["synthetic"]
    features: Features


class FallFeatures(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    magnitude_max_g: float = Field(strict=True)
    magnitude_min_g: float = Field(strict=True)
    magnitude_mean_g: float = Field(strict=True)
    magnitude_std_g: float = Field(strict=True)
    magnitude_p95_g: float = Field(strict=True)
    jerk_rms_g_s: float = Field(strict=True)
    orientation_change_deg: float = Field(strict=True)


class FallRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source: Literal["dataset_replay"]
    sample_rate_hz: int = Field(strict=True, ge=200, le=200)
    record_complete: Literal[True]
    features: FallFeatures


def build_router(current_user, get_database):
    router = APIRouter(prefix="/api/v1/ai", tags=["Synthetic educational model"])
    lock = threading.Lock()
    slots = threading.BoundedSemaphore(2)
    cached = []
    fall_cached = []

    def model():
        with lock:
            if not cached:
                try:
                    from .ml import ModelService
                    cached.append(ModelService())
                except (ImportError, OSError, ValueError):
                    raise HTTPException(503, "Synthetic model unavailable; train the local model first") from None
            return cached[0]

    def fall_model():
        with lock:
            if not fall_cached:
                try:
                    from .fall_ml import FallModelService
                    fall_cached.append(FallModelService())
                except (ImportError, OSError, ValueError):
                    raise HTTPException(503, "Dataset replay model unavailable; train locally first") from None
            return fall_cached[0]

    @router.get("/fall/examples")
    def fall_examples(user=Depends(current_user)):
        import json
        service = fall_model()
        return json.loads((service.model_dir / "replay_examples.json").read_text())

    @router.post("/fall-classify")
    def classify_fall(body: FallRequest, user=Depends(current_user), db=Depends(get_database)):
        if not db.rate_limit("ai:user", user["id"], 30, 60):
            raise HTTPException(429, "Wait before running another replay")
        if not slots.acquire(blocking=False):
            raise HTTPException(503, "Replay model busy")
        try:
            return fall_model().predict(body.features.model_dump(),source=body.source,
                sample_rate_hz=body.sample_rate_hz,record_complete=body.record_complete)
        except ValueError:
            raise HTTPException(422, "Features do not match the full-record SisFall replay contract") from None
        finally:
            slots.release()

    @router.get("/model")
    def model_metadata(user=Depends(current_user)):
        service = model()
        return {key:service.metadata[key] for key in (
            "model_id", "feature_names", "educational_only", "limitations", "selected_model"
        )}

    @router.post("/classify")
    def classify(body: ClassificationRequest, user=Depends(current_user), db=Depends(get_database)):
        if not db.rate_limit("ai:user", user["id"], 30, 60):
            raise HTTPException(429, "Wait before running another synthetic classification")
        if not slots.acquire(blocking=False):
            raise HTTPException(503, "Synthetic model busy; try again later")
        try:
            return model().predict(body.features.model_dump(),source=body.source)
        except ValueError:
            raise HTTPException(422, "Sample is outside the synthetic model contract") from None
        finally:
            slots.release()

    return router
