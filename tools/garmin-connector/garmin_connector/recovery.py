"""Daily Garmin recovery metrics (Phase 6F): HRV, sleep, resting HR, Body Battery, stress.

Field names are taken from python-garminconnect 0.3.16 itself, never guessed:

* ``get_hrv_data(date)`` -> ``hrvSummary.{calendarDate,lastNightAvg,weeklyAvg,status}``;
  ``None`` / ``{}`` when Garmin answers 204 (no HRV for that date).
  Source: ``typed.HrvSummary`` and the library's own ``tests/test_garmin.py::test_hrv_data``.
* ``get_sleep_data(date)`` -> ``dailySleepDTO.{calendarDate,sleepTimeSeconds}`` and
  ``dailySleepDTO.sleepScores.overall.value``. Source: ``typed.DailySleepDTO`` / ``typed.SleepScores``.
* ``get_rhr_daily(date, date)`` -> ``[{"calendarDate", "value"}]``; the library itself flattens the
  wellness-stats response into that shape (``Garmin.get_rhr_daily``), so no raw schema is read here.
* ``get_body_battery(date)`` -> ``[{"date", "charged", "drained", "bodyBatteryValuesArray"}]`` where
  the values array holds ``[timestamp, level]`` pairs. Source: ``typed.BodyBatteryEntry``.
* ``get_all_day_stress(date)`` -> ``{calendarDate, avgStressLevel, maxStressLevel, ...}``. Source: the
  library's own ``tests/test_garmin.py::test_all_day_stress`` (``typed.py`` has no stress model).

``typed.py`` describes itself as *experimental*; it is used only as documentation of the field names,
never imported (it needs pydantic, which the connector does not depend on).

The connector only *projects* the few documented fields out of each response, checks their types and
keeps Garmin's own units (seconds, bpm, 0-100 scales, milliseconds for HRV). It never converts units,
never computes a baseline and never judges a value: that is Spring's job. No time series, no profile
id and no raw body leave this module.
"""

from __future__ import annotations

import logging
import math
from collections.abc import Callable
from datetime import date
from typing import Any

from garminconnect.exceptions import GarminConnectAuthenticationError, GarminConnectTooManyRequestsError

from .errors import GARMIN_AUTH_REQUIRED, GARMIN_RATE_LIMITED, translate

logger = logging.getLogger(__name__)

OK = "OK"
NO_DATA = "NO_DATA"
MALFORMED = "MALFORMED"
ERROR = "ERROR"

METRICS = ("hrv", "sleep", "restingHeartRate", "bodyBattery", "stress")

# HRV status is a Garmin label (e.g. BALANCED); it is passed through, but only as a short token.
_MAX_STATUS_LENGTH = 32


class MalformedResponse(Exception):
    """The response exists but does not have the documented shape."""


def _number(value: Any) -> float | None:
    """A finite JSON number, else ``None``. ``bool`` is rejected (it is an ``int`` in Python)."""
    if value is None or isinstance(value, bool) or not isinstance(value, int | float):
        return None
    number = float(value)
    return number if math.isfinite(number) else None


def _non_negative(value: Any) -> float | None:
    number = _number(value)
    return number if number is not None and number >= 0 else None


def _int(value: float | None) -> int | None:
    return None if value is None else int(round(value))


def _require_dict(value: Any, what: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise MalformedResponse(f"{what} is not an object")
    return value


def _check_date(found: Any, requested: str, what: str) -> None:
    """Garmin echoes the calendar date; a different date would be stored under the wrong day."""
    if found is None:
        return
    if not isinstance(found, str) or found[:10] != requested:
        raise MalformedResponse(f"{what} is for a different date")


def parse_hrv(raw: Any, requested: str) -> dict[str, Any] | None:
    if raw is None or raw == {}:
        return None
    summary = _require_dict(raw, "hrv").get("hrvSummary")
    if summary is None:
        return None
    summary = _require_dict(summary, "hrvSummary")
    _check_date(summary.get("calendarDate"), requested, "hrvSummary")
    status = summary.get("status")
    if not (isinstance(status, str) and 0 < len(status) <= _MAX_STATUS_LENGTH and status.replace("_", "").isalnum()):
        status = None
    data = {
        "lastNightAvg": _non_negative(summary.get("lastNightAvg")),
        "weeklyAvg": _non_negative(summary.get("weeklyAvg")),
        "status": status,
    }
    return data if any(v is not None for v in data.values()) else None


def parse_sleep(raw: Any, requested: str) -> dict[str, Any] | None:
    if raw is None or raw == {}:
        return None
    dto = _require_dict(raw, "sleep").get("dailySleepDTO")
    if dto is None:
        return None
    dto = _require_dict(dto, "dailySleepDTO")
    _check_date(dto.get("calendarDate"), requested, "dailySleepDTO")
    score = None
    scores = dto.get("sleepScores")
    if isinstance(scores, dict) and isinstance(scores.get("overall"), dict):
        score = _non_negative(scores["overall"].get("value"))
    seconds = _non_negative(dto.get("sleepTimeSeconds"))
    data = {"sleepTimeSeconds": _int(seconds), "sleepScore": _int(score)}
    return data if any(v is not None for v in data.values()) else None


def parse_resting_heart_rate(raw: Any, requested: str) -> dict[str, Any] | None:
    if raw is None:
        return None
    if not isinstance(raw, list):
        raise MalformedResponse("resting heart rate is not a list")
    for row in raw:
        if isinstance(row, dict) and row.get("calendarDate") == requested:
            value = _number(row.get("value"))
            if value is None or value <= 0:
                raise MalformedResponse("resting heart rate value is not a positive number")
            return {"value": _int(value)}
    return None


def parse_body_battery(raw: Any, requested: str) -> dict[str, Any] | None:
    if raw is None:
        return None
    if not isinstance(raw, list):
        raise MalformedResponse("body battery is not a list")
    entry = next((e for e in raw if isinstance(e, dict) and e.get("date") == requested), None)
    if entry is None:
        return None
    levels: list[float] = []
    values = entry.get("bodyBatteryValuesArray")
    if isinstance(values, list):
        for pair in values:
            if isinstance(pair, list) and len(pair) >= 2:
                level = _non_negative(pair[1])
                if level is not None:
                    levels.append(level)
    data = {
        "highest": _int(max(levels)) if levels else None,
        "lowest": _int(min(levels)) if levels else None,
        "charged": _int(_non_negative(entry.get("charged"))),
        "drained": _int(_non_negative(entry.get("drained"))),
    }
    return data if any(v is not None for v in data.values()) else None


def parse_stress(raw: Any, requested: str) -> dict[str, Any] | None:
    if raw is None or raw == {}:
        return None
    raw = _require_dict(raw, "stress")
    _check_date(raw.get("calendarDate"), requested, "stress")
    # Garmin's stress scale is 0-100; a negative number is a "no reading" marker, not a level.
    data = {
        "avgStressLevel": _int(_non_negative(raw.get("avgStressLevel"))),
        "maxStressLevel": _int(_non_negative(raw.get("maxStressLevel"))),
    }
    return data if any(v is not None for v in data.values()) else None


def _metric(
    name: str, call: Callable[[], Any], parse: Callable[[Any, str], dict[str, Any] | None], requested: str
) -> dict[str, Any]:
    """Fetch and parse one metric. Authentication and rate limiting abort the whole request
    (the caller must stop, not carry on hitting Garmin); any other failure only marks this metric."""
    try:
        raw = call()
    except (GarminConnectAuthenticationError, GarminConnectTooManyRequestsError) as exc:
        raise translate(exc) from exc
    except Exception as exc:  # noqa: BLE001 - becomes a per-metric error, never the raw message
        err = translate(exc)
        if err.code in (GARMIN_AUTH_REQUIRED, GARMIN_RATE_LIMITED):
            raise err from exc
        logger.warning("Garmin recovery metric %s failed: code=%s", name, err.code)
        return {"status": ERROR, "data": None, "error": err.code}
    try:
        data = parse(raw, requested)
    except MalformedResponse as exc:
        logger.warning("Garmin recovery metric %s malformed: %s", name, exc)
        return {"status": MALFORMED, "data": None}
    return {"status": OK, "data": data} if data is not None else {"status": NO_DATA, "data": None}


def validate_date(value: str) -> str:
    try:
        return date.fromisoformat(value).isoformat()
    except (TypeError, ValueError) as exc:
        raise ValueError("date must be YYYY-MM-DD") from exc


def fetch_recovery(garmin: Any, requested: str) -> dict[str, Any]:
    """One day's recovery metrics, one Garmin call per metric, strictly sequential, no retry here.

    (python-garminconnect itself retries only 5xx / network failures, never 401 or 429.)
    """
    requested = validate_date(requested)
    metrics = {
        "hrv": _metric("hrv", lambda: garmin.get_hrv_data(requested), parse_hrv, requested),
        "sleep": _metric("sleep", lambda: garmin.get_sleep_data(requested), parse_sleep, requested),
        "restingHeartRate": _metric(
            "restingHeartRate", lambda: garmin.get_rhr_daily(requested, requested), parse_resting_heart_rate, requested
        ),
        "bodyBattery": _metric("bodyBattery", lambda: garmin.get_body_battery(requested), parse_body_battery, requested),
        "stress": _metric("stress", lambda: garmin.get_all_day_stress(requested), parse_stress, requested),
    }
    available = [name for name in METRICS if metrics[name]["status"] == OK]
    logger.info("Fetched Garmin recovery for %s: available=%s", requested, ",".join(available) or "-")
    return {"date": requested, "metrics": metrics}

