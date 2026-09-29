"""Localhost-only HTTP surface consumed by the Spring server.

Two endpoints, no login endpoint, no docs UI. ``/activities`` returns the Garmin
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

logger = logging.getLogger(__name__)

SERVICE_NAME = "garmin-connector"

ActivitiesFetcher = Callable[[int], list[dict[str, Any]]]


def create_app(fetch_activities: ActivitiesFetcher) -> FastAPI:
    """Build the app around a ``fetch_activities(limit) -> list[dict]`` callable
    (production: ``CachedGatewayProvider.recent_activities``; tests: a fake)."""
    app = FastAPI(title=SERVICE_NAME, docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    def health() -> dict[str, str]:
        # Process liveness only; Garmin login state does not make this DOWN.
        return {"status": "UP", "service": SERVICE_NAME}

    @app.get("/activities")
    def activities(limit: int = Query(20, ge=1, le=MAX_LIMIT)) -> JSONResponse:
        try:
            items = fetch_activities(limit)
        except Exception as exc:  # noqa: BLE001 - everything becomes the error contract
            err = translate(exc)
            logger.warning("GET /activities failed: code=%s status=%d", err.code, err.http_status)
            raise err from exc
        logger.info("GET /activities limit=%d -> %d item(s)", limit, len(items))
        return JSONResponse(content=items)

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
