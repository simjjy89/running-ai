"""Shared fakes. No test here talks to Garmin or the network."""

from __future__ import annotations

from typing import Any

import pytest


def synthetic_item(activity_id: int = 188081596, type_key: str = "running") -> dict[str, Any]:
    """One Garmin activity-list item shaped like the confirmed contract (synthetic values)."""
    return {
        "activityId": activity_id,
        "activityName": "Morning Run",
        "startTimeLocal": "2026-09-29 06:30:00",
        "startTimeGMT": "2026-09-28 21:30:00",
        "activityType": {"typeId": 1, "typeKey": type_key, "parentTypeId": 17, "isHidden": False},
        "distance": 10000.0,
        "duration": 3600.0,
        "elapsedDuration": 3650.0,
        "movingDuration": 3590.0,
        "averageSpeed": 2.777,
        "calories": 720.0,
        "averageHR": 155.0,
        "maxHR": 172.0,
        "hasPolyline": False,
        "privacy": {"typeId": 2, "typeKey": "private"},
    }


def synthetic_lactate_threshold(heart_rate: int | None = 180, speed: float | None = 0.34444348) -> dict[str, Any]:
    """Shaped like the live-probed contract (Phase 6D-0); all identifiers are synthetic."""
    return {
        "speed_and_heart_rate": {
            "userProfilePK": 1,
            "version": 1,
            "calendarDate": "2026-10-01T00:00:00.0",
            "sequence": 1,
            "speed": speed,
            "heartRate": heart_rate,
            "heartRateCycling": None,
        },
        "power": {
            "userProfilePk": 1,
            "calendarDate": "2026-10-01T00:00:00.0",
            "origin": "power",
            "sport": "RUNNING",
            "functionalThresholdPower": 300,
            "weight": 70.0,
            "powerToWeight": 4.3,
            "ftpCreateTime": "2026-10-01T00:00:00.0",
            "weightCreateTime": "2026-09-17T00:00:00.0",
            "isStale": False,
        },
    }


RECOVERY_DATE = "2026-10-02"


def synthetic_hrv(day: str = RECOVERY_DATE) -> dict[str, Any]:
    """Shaped like python-garminconnect 0.3.16 ``typed.HrvData`` (synthetic values)."""
    return {
        "userProfilePK": 1,
        "hrvSummary": {
            "calendarDate": day,
            "weeklyAvg": 48.5,
            "lastNightAvg": 52.0,
            "lastNight5MinHigh": 71.0,
            "status": "BALANCED",
            "feedbackPhrase": "BALANCED_1",
            "baseline": {"lowUpper": 42.0, "balancedLow": 43.0, "balancedUpper": 58.0, "markerValue": 0.5},
        },
        "hrvReadings": [{"hrvValue": 50, "readingTimeGMT": "2026-10-01T22:00:00.0"}],
    }


def synthetic_sleep(day: str = RECOVERY_DATE) -> dict[str, Any]:
    """Shaped like ``typed.SleepData`` (synthetic values)."""
    return {
        "dailySleepDTO": {
            "userProfilePK": 1,
            "calendarDate": day,
            "sleepTimeSeconds": 25200,
            "deepSleepSeconds": 5400,
            "sleepScores": {"overall": {"value": 84, "qualifierKey": "GOOD"}},
        },
        "sleepMovement": [{"startGMT": "2026-10-01T22:00:00.0", "activityLevel": 1.0}],
    }


def synthetic_rhr(day: str = RECOVERY_DATE) -> list[dict[str, Any]]:
    """Exactly the shape ``Garmin.get_rhr_daily`` itself builds."""
    return [{"calendarDate": day, "value": 52}]


def synthetic_body_battery(day: str = RECOVERY_DATE) -> list[dict[str, Any]]:
    """Shaped like ``typed.BodyBatteryEntry`` (synthetic values)."""
    return [
        {
            "date": day,
            "charged": 58,
            "drained": 32,
            "bodyBatteryValuesArray": [[1790000000000, 65], [1790000300000, 81], [1790000600000, 40]],
        }
    ]


def synthetic_stress(day: str = RECOVERY_DATE) -> dict[str, Any]:
    """Shaped like the fields the library's own ``test_all_day_stress`` asserts (synthetic values)."""
    return {
        "userProfilePK": 1,
        "calendarDate": day,
        "avgStressLevel": 29,
        "maxStressLevel": 88,
        "stressValuesArray": [[1790000000000, 25], [1790000180000, -1]],
    }


class FakeGarmin:
    """Stands in for garminconnect.Garmin in tests."""

    def __init__(self, email: str | None = None, password: str | None = None, **kwargs: Any) -> None:
        self.email = email
        self.password = password
        self.kwargs = kwargs
        self.display_name = "synthetic-user"
        self.login_calls: list[str | None] = []
        self.activities_calls: list[tuple[int, int]] = []
        self.lactate_threshold_calls: int = 0
        self.login_error: Exception | None = None
        self.activities_error: Exception | None = None
        self.lactate_threshold_error: Exception | None = None
        self.activities_result: Any = [synthetic_item()]
        self.lactate_threshold_result: Any = synthetic_lactate_threshold()
        self.recovery_calls: list[tuple[str, tuple[Any, ...]]] = []
        self.recovery_results: dict[str, Any] = {
            "get_hrv_data": synthetic_hrv(),
            "get_sleep_data": synthetic_sleep(),
            "get_rhr_daily": synthetic_rhr(),
            "get_body_battery": synthetic_body_battery(),
            "get_all_day_stress": synthetic_stress(),
        }
        self.recovery_errors: dict[str, Exception] = {}

    def login(self, tokenstore: str | None = None) -> tuple[None, None]:
        self.login_calls.append(tokenstore)
        if self.login_error is not None:
            raise self.login_error
        return None, None

    def get_activities(self, start: int = 0, limit: int = 20) -> Any:
        self.activities_calls.append((start, limit))
        if self.activities_error is not None:
            raise self.activities_error
        return self.activities_result

    def get_lactate_threshold(self, **kwargs: Any) -> Any:
        self.lactate_threshold_calls += 1
        if self.lactate_threshold_error is not None:
            raise self.lactate_threshold_error
        return self.lactate_threshold_result


    def _recovery(self, method: str, *args: Any) -> Any:
        self.recovery_calls.append((method, args))
        if method in self.recovery_errors:
            raise self.recovery_errors[method]
        return self.recovery_results[method]

    def get_hrv_data(self, cdate: str) -> Any:
        return self._recovery("get_hrv_data", cdate)

    def get_sleep_data(self, cdate: str) -> Any:
        return self._recovery("get_sleep_data", cdate)

    def get_rhr_daily(self, start: str, end: str) -> Any:
        return self._recovery("get_rhr_daily", start, end)

    def get_body_battery(self, startdate: str, enddate: str | None = None) -> Any:
        return self._recovery("get_body_battery", startdate)

    def get_all_day_stress(self, cdate: str) -> Any:
        return self._recovery("get_all_day_stress", cdate)


@pytest.fixture
def fake_garmin() -> FakeGarmin:
    return FakeGarmin()
