"""Structured error contract exposed by the connector.

Every failure the Spring server can see is one of these codes. Messages never
contain credentials, tokens, cookies or payloads.
"""

from __future__ import annotations

from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectNotFoundError,
    GarminConnectTooManyRequestsError,
)

GARMIN_AUTH_REQUIRED = "GARMIN_AUTH_REQUIRED"
GARMIN_FORBIDDEN = "GARMIN_FORBIDDEN"
GARMIN_RATE_LIMITED = "GARMIN_RATE_LIMITED"
GARMIN_NOT_FOUND = "GARMIN_NOT_FOUND"
GARMIN_UPSTREAM_ERROR = "GARMIN_UPSTREAM_ERROR"
GARMIN_CONNECTOR_ERROR = "GARMIN_CONNECTOR_ERROR"
INVALID_REQUEST = "INVALID_REQUEST"

HTTP_STATUS = {
    GARMIN_AUTH_REQUIRED: 401,
    GARMIN_FORBIDDEN: 403,
    GARMIN_RATE_LIMITED: 429,
    GARMIN_NOT_FOUND: 404,
    GARMIN_UPSTREAM_ERROR: 502,
    GARMIN_CONNECTOR_ERROR: 500,
    INVALID_REQUEST: 400,
}


class ConnectorError(Exception):
    """A failure with a stable code and a safe, credential-free message."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.http_status = HTTP_STATUS[code]

    def to_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


def _upstream_status(exc: BaseException) -> int | None:
    response = getattr(exc, "response", None)
    status = getattr(response, "status_code", None)
    return status if isinstance(status, int) else None


def translate(exc: BaseException) -> ConnectorError:
    """Map a garminconnect / unexpected exception to the connector error contract.

    The original exception text is deliberately not forwarded: library messages can
    embed response bodies or URLs.
    """
    if isinstance(exc, ConnectorError):
        return exc
    if isinstance(exc, GarminConnectAuthenticationError):
        return ConnectorError(
            GARMIN_AUTH_REQUIRED,
            "Garmin authentication required: no valid token store; run 'python -m garmin_connector login' on this host",
        )
    if isinstance(exc, GarminConnectTooManyRequestsError):
        return ConnectorError(GARMIN_RATE_LIMITED, "Garmin request was rate limited; do not retry automatically")
    # Before the generic connection error: the not-found error subclasses it (python-garminconnect 0.3.16).
    if isinstance(exc, GarminConnectNotFoundError):
        return ConnectorError(GARMIN_NOT_FOUND, "Garmin has no such resource (404)")
    if isinstance(exc, GarminConnectConnectionError):
        status = _upstream_status(exc)
        if status == 403:
            return ConnectorError(GARMIN_FORBIDDEN, "Garmin refused the request (403)")
        if status == 401:
            return ConnectorError(GARMIN_AUTH_REQUIRED, "Garmin rejected the session (401); log in again on this host")
        if status == 429:
            return ConnectorError(GARMIN_RATE_LIMITED, "Garmin request was rate limited; do not retry automatically")
        if status == 404:
            return ConnectorError(GARMIN_NOT_FOUND, "Garmin has no such resource (404)")
        suffix = f" (upstream status {status})" if status else ""
        return ConnectorError(GARMIN_UPSTREAM_ERROR, f"Garmin Connect request failed{suffix}")
    return ConnectorError(GARMIN_CONNECTOR_ERROR, f"Unexpected connector error: {type(exc).__name__}")
