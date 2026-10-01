"""Read-only Garmin transport on top of python-garminconnect 0.3.16.

Only token-based sessions are used here: this module never sees an email or a
password. Interactive login lives in :mod:`garmin_connector.auth`.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from garminconnect import Garmin

from .errors import GARMIN_UPSTREAM_ERROR, ConnectorError, translate

logger = logging.getLogger(__name__)

# python-garminconnect's documented default: <tokenstore>/garmin_tokens.json (mode 0600).
DEFAULT_TOKENSTORE = "~/.garminconnect"

MAX_LIMIT = 100


def unwrap_activity_list(result: Any) -> list[dict[str, Any]]:
    """Return the activity items as a plain list.

    Garmin's activity search has been observed as both a bare JSON array and a
    ``{"activityList": [...]}`` object (python-garminconnect handles both in
    ``get_last_activity``); the connector accepts both and exposes only the array.
    Items are passed through untouched.
    """
    if result is None:
        return []
    if isinstance(result, list):
        return result
    if isinstance(result, dict) and isinstance(result.get("activityList"), list):
        return result["activityList"]
    raise ConnectorError(GARMIN_UPSTREAM_ERROR, "Unexpected activity list shape from Garmin Connect")


class GarminGateway:
    """Thin wrapper around an authenticated ``garminconnect.Garmin`` instance."""

    def __init__(self, garmin: Any) -> None:
        self._garmin = garmin

    @classmethod
    def from_tokens(
        cls,
        tokenstore: str = DEFAULT_TOKENSTORE,
        garmin_factory: Callable[[], Any] = Garmin,
    ) -> "GarminGateway":
        """Resume a session from the local token store only.

        ``Garmin()`` is created without credentials, so python-garminconnect can
        only load ``garmin_tokens.json`` (refreshing the DI token if it is about to
        expire) and verify it with one profile request. If the store is missing or
        rejected it raises ``GarminConnectAuthenticationError`` — it can never fall
        back to a credential login from here, which is exactly what we want.
        """
        garmin = garmin_factory()
        garmin.login(tokenstore)
        return cls(garmin)

    @property
    def display_name(self) -> str | None:
        return getattr(self._garmin, "display_name", None)

    def recent_activities(self, limit: int, start: int = 0) -> list[dict[str, Any]]:
        """``limit`` activity-list items starting at offset ``start`` (0 = most recent), raw, newest first."""
        if start < 0:
            raise ValueError("start must be >= 0")
        if not 1 <= limit <= MAX_LIMIT:
            raise ValueError(f"limit must be between 1 and {MAX_LIMIT}")
        try:
            result = self._garmin.get_activities(start, limit)
        except Exception as exc:  # noqa: BLE001 - translated into the error contract
            raise translate(exc) from exc
        items = unwrap_activity_list(result)
        logger.info("Fetched %d Garmin activity item(s) (start=%d, limit=%d)", len(items), start, limit)
        return items

    def lactate_threshold(self) -> dict[str, Any]:
        """Latest running lactate-threshold snapshot (heart rate, speed, power), raw and unprocessed.

        Field names/units are not documented by Garmin or by python-garminconnect; normalisation
        and unit conversion are Spring's responsibility (see the Phase 6D work order for the
        live-probed contract). This wrapper only authenticates, calls through and translates errors.
        """
        try:
            result = self._garmin.get_lactate_threshold()
        except Exception as exc:  # noqa: BLE001 - translated into the error contract
            raise translate(exc) from exc
        if not isinstance(result, dict):
            raise ConnectorError(GARMIN_UPSTREAM_ERROR, "Unexpected lactate threshold shape from Garmin Connect")
        logger.info("Fetched Garmin lactate threshold snapshot")
        return result


class CachedGatewayProvider:
    """Creates the gateway lazily and reuses it while the session stays valid.

    An authentication failure drops the cached gateway so the next request reloads
    the token store (a human may have logged in again in the meantime). Nothing here
    retries or re-authenticates on its own.
    """

    def __init__(
        self,
        tokenstore: str = DEFAULT_TOKENSTORE,
        garmin_factory: Callable[[], Any] = Garmin,
    ) -> None:
        self._tokenstore = tokenstore
        self._garmin_factory = garmin_factory
        self._gateway: GarminGateway | None = None

    def __call__(self) -> GarminGateway:
        if self._gateway is None:
            try:
                self._gateway = GarminGateway.from_tokens(self._tokenstore, self._garmin_factory)
            except Exception as exc:  # noqa: BLE001
                raise translate(exc) from exc
        return self._gateway

    def recent_activities(self, limit: int, start: int = 0) -> list[dict[str, Any]]:
        gateway = self()
        try:
            return gateway.recent_activities(limit, start)
        except ConnectorError as err:
            if err.code == "GARMIN_AUTH_REQUIRED":
                self._gateway = None
            raise

    def lactate_threshold(self) -> dict[str, Any]:
        gateway = self()
        try:
            return gateway.lactate_threshold()
        except ConnectorError as err:
            if err.code == "GARMIN_AUTH_REQUIRED":
                self._gateway = None
            raise
