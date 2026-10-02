"""Localhost-only HTTP surface consumed by the Spring server.

A handful of read-only endpoints, no login endpoint, no docs UI. ``/activities`` returns the Garmin
activity-list items exactly as received (a JSON array), so all normalisation stays
in the Spring ``GarminActivityMapper``.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .client import MAX_LIMIT
from .errors import GARMIN_CONNECTOR_ERROR, INVALID_REQUEST, ConnectorError, translate
from .recovery import validate_date

logger = logging.getLogger(__name__)

SERVICE_NAME = "garmin-connector"

ActivitiesFetcher = Callable[[int, int], list[dict[str, Any]]]
LactateThresholdFetcher = Callable[[], dict[str, Any]]
RecoveryFetcher = Callable[[str], dict[str, Any]]


def create_app(
    fetch_activities: ActivitiesFetcher,
    fetch_lactate_threshold: LactateThresholdFetcher,
    fetch_recovery: RecoveryFetcher,
) -> FastAPI:
    """Build the app around a ``fetch_activities(limit, start) -> list[dict]`` callable, a
    ``fetch_lactate_threshold() -> dict`` callable and a ``fetch_recovery(date) -> dict`` callable
    (production: ``CachedGatewayProvider``'s ``recent_activities``/``lactate_threshold``/``recovery``;
    tests: fakes)."""
    app = FastAPI(title=SERVICE_NAME, docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    def health() -> dict[str, str]:
        # Process liveness only; Garmin login state does not make this DOWN.
        return {"status": "UP", "service": SERVICE_NAME}

    @app.get("/activities")
    def activities(
        limit: int = Query(20, ge=1, le=MAX_LIMIT),
        start: int = Query(0, ge=0),
    ) -> JSONResponse:
        try:
            items = fetch_activities(limit, start)
        except Exception as exc:  # noqa: BLE001 - everything becomes the error contract
            err = translate(exc)
            logger.warning("GET /activities failed: code=%s status=%d", err.code, err.http_status)
            raise err from exc
        logger.info("GET /activities start=%d limit=%d -> %d item(s)", start, limit, len(items))
        return JSONResponse(content=items)

    @app.get("/lactate-threshold")
    def lactate_threshold() -> JSONResponse:
        try:
            result = fetch_lactate_threshold()
        except Exception as exc:  # noqa: BLE001 - everything becomes the error contract
            err = translate(exc)
            logger.warning("GET /lactate-threshold failed: code=%s status=%d", err.code, err.http_status)
            raise err from exc
        logger.info("GET /lactate-threshold -> ok")
        return JSONResponse(content=result)

    @app.get("/recovery")
    def recovery(date: str = Query(..., pattern=r"^\d{4}-\d{2}-\d{2}$")) -> JSONResponse:
        try:
            day = validate_date(date)
        except ValueError as exc:
            raise ConnectorError(INVALID_REQUEST, "Invalid request parameter(s): date") from exc
        try:
            result = fetch_recovery(day)
        except Exception as exc:  # noqa: BLE001 - everything becomes the error contract
            err = translate(exc)
            logger.warning("GET /recovery failed: code=%s status=%d", err.code, err.http_status)
            raise err from exc
        logger.info("GET /recovery date=%s -> ok", day)
        return JSONResponse(content=result)

    @app.exception_handler(ConnectorError)
    def connector_error(_: Request, err: ConnectorError) -> JSONResponse:
        return JSONResponse(status_code=err.http_status, content=err.to_dict())

    @app.exception_handler(RequestValidationError)
    def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        fields = sorted({str(e.get("loc", ("?",))[-1]) for e in exc.errors()})
        return JSONResponse(
            status_code=400,
            content={"code": INVALID_REQUEST, "message": f"Invalid request parameter(s): {', '.join(fields)}"},
        )

    @app.exception_handler(Exception)
    def unexpected_error(_: Request, exc: Exception) -> JSONResponse:
        logger.error("Unhandled connector error: %s", type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content={"code": GARMIN_CONNECTOR_ERROR, "message": "Unexpected connector error"},
        )

    return app
