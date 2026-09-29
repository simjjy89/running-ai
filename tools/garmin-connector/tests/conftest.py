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


class FakeGarmin:
    """Stands in for garminconnect.Garmin in tests."""

    def __init__(self, email: str | None = None, password: str | None = None, **kwargs: Any) -> None:
        self.email = email
        self.password = password
        self.kwargs = kwargs
        self.display_name = "synthetic-user"
        self.login_calls: list[str | None] = []
        self.activities_calls: list[tuple[int, int]] = []
        self.login_error: Exception | None = None
        self.activities_error: Exception | None = None
        self.activities_result: Any = [synthetic_item()]

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


@pytest.fixture
def fake_garmin() -> FakeGarmin:
    return FakeGarmin()
