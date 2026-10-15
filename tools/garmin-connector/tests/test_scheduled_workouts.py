"""Phase 8B: reading Garmin's workout calendar, and never writing to it.

The reason this endpoint exists is that RunningAI publishes to Intervals.icu, and the hop from
Intervals.icu to Garmin Connect is Intervals.icu's own integration - entirely outside this
repository. Nothing in RunningAI could previously tell whether a published workout had actually
arrived; the only evidence was one person looking at a watch, once.

The risk this file guards is the mirror image: python-garminconnect also offers
``schedule_workout``, ``unschedule_workout``, ``upload_*_workout`` and ``push_workout_to_device``.
None of them may ever be reachable from here. The tests below assert the read works AND that the
write surface stays absent, so adding one later breaks the build rather than passing review.

No test here talks to Garmin or the network.
"""

from __future__ import annotations

import ast
import inspect
from typing import Any

import pytest
from fastapi.testclient import TestClient
from garminconnect import GarminConnectAuthenticationError, GarminConnectTooManyRequestsError

from garmin_connector import client as client_module
from garmin_connector.api import create_app
from garmin_connector.client import CachedGatewayProvider, GarminGateway

from .conftest import FakeGarmin, synthetic_scheduled_workouts


def _unused(*args: Any, **kwargs: Any):
    raise AssertionError("not used by this suite")


class FakeScheduledFetcher:
    def __init__(self) -> None:
        self.calls: list[tuple[int, int]] = []
        self.result: Any = synthetic_scheduled_workouts()
        self.error: Exception | None = None

    def __call__(self, year: int, month: int) -> Any:
        self.calls.append((year, month))
        if self.error is not None:
            raise self.error
        return self.result


@pytest.fixture
def scheduled() -> FakeScheduledFetcher:
    return FakeScheduledFetcher()


@pytest.fixture
def http(scheduled: FakeScheduledFetcher) -> TestClient:
    return TestClient(
        create_app(_unused, _unused, _unused, _unused, scheduled),
        raise_server_exceptions=False,
    )


# ---- the read ---------------------------------------------------------------------------------


def test_the_body_is_returned_untouched(http, scheduled):
    scheduled.result = {"calendarItems": [{"itemType": "workout", "anything": [1, {"nested": None}]}]}

    response = http.get("/scheduled-workouts", params={"year": 2026, "month": 10})

    assert response.status_code == 200
    assert response.json() == scheduled.result


def test_the_requested_month_is_passed_through_as_given(http, scheduled):
    http.get("/scheduled-workouts", params={"year": 2026, "month": 10})

    # 1-based here and at the gateway; the library is what subtracts one for Garmin's 0-indexed
    # months, and this connector must not do it a second time.
    assert scheduled.calls == [(2026, 10)]


@pytest.mark.parametrize(
    "params",
    [
        {"year": 2026},
        {"month": 10},
        {"year": 2026, "month": 0},
        {"year": 2026, "month": 13},
        {"year": 1999, "month": 1},
        {"year": "soon", "month": 10},
    ],
)
def test_a_bad_request_is_rejected_before_garmin_is_touched(http, scheduled, params):
    response = http.get("/scheduled-workouts", params=params)

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
    assert scheduled.calls == []


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (GarminConnectAuthenticationError("expired"), 401, "GARMIN_AUTH_REQUIRED"),
        (GarminConnectTooManyRequestsError("slow down"), 429, "GARMIN_RATE_LIMITED"),
    ],
)
def test_garmin_failures_use_the_existing_error_contract(http, scheduled, error, status, code):
    scheduled.error = error

    response = http.get("/scheduled-workouts", params={"year": 2026, "month": 10})

    assert response.status_code == status
    assert response.json()["code"] == code


# ---- the gateway ------------------------------------------------------------------------------


def test_the_gateway_makes_exactly_one_garmin_call(fake_garmin: FakeGarmin):
    gateway = GarminGateway(fake_garmin)

    result = gateway.scheduled_workouts(2026, 10)

    assert fake_garmin.scheduled_workout_calls == [(2026, 10)]
    assert result == fake_garmin.scheduled_workouts_result


@pytest.mark.parametrize(("year", "month"), [(1999, 1), (3000, 1), (2026, 0), (2026, 13)])
def test_the_gateway_validates_before_calling(fake_garmin: FakeGarmin, year, month):
    with pytest.raises(ValueError):
        GarminGateway(fake_garmin).scheduled_workouts(year, month)

    assert fake_garmin.scheduled_workout_calls == []


def test_an_unexpected_shape_is_an_upstream_error_rather_than_being_passed_on(fake_garmin: FakeGarmin):
    fake_garmin.scheduled_workouts_result = "not json"

    with pytest.raises(Exception) as excinfo:
        GarminGateway(fake_garmin).scheduled_workouts(2026, 10)

    assert getattr(excinfo.value, "code", None) == "GARMIN_UPSTREAM_ERROR"


def test_an_expired_session_is_dropped_so_the_next_request_reloads_the_token_store():
    fake = FakeGarmin()
    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=lambda: fake)
    provider.scheduled_workouts(2026, 10)
    fake.scheduled_workouts_error = GarminConnectAuthenticationError("expired")

    with pytest.raises(Exception):
        provider.scheduled_workouts(2026, 10)

    assert len(fake.login_calls) == 1
    fake.scheduled_workouts_error = None
    provider.scheduled_workouts(2026, 10)
    assert len(fake.login_calls) == 2


# ---- the write surface that must stay absent ----------------------------------------------------

GARMIN_WRITE_METHODS = [
    "schedule_workout",
    "unschedule_workout",
    "upload_workout",
    "update_workout",
    "delete_workout",
    "upload_running_workout",
    "push_workout_to_device",
]


def test_the_library_really_does_offer_the_write_methods_this_connector_refuses():
    # Otherwise the guard below would pass vacuously after a library change.
    from garminconnect import Garmin

    for name in GARMIN_WRITE_METHODS:
        assert hasattr(Garmin, name), f"{name} is gone from python-garminconnect; revisit this guard"


def test_no_garmin_write_method_is_reachable_from_the_connector():
    # Parsed, not grepped: the prose in this module names those methods precisely in order to say
    # it does not call them, and a text scan cannot tell the two apart.
    tree = ast.parse(inspect.getsource(client_module))
    called = {
        node.func.attr
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
    }
    attributes = {node.attr for node in ast.walk(tree) if isinstance(node, ast.Attribute)}

    forbidden = set(GARMIN_WRITE_METHODS)
    assert called & forbidden == set(), f"the connector calls a Garmin write method: {called & forbidden}"
    assert attributes & forbidden == set(), f"the connector references a Garmin write method: {attributes & forbidden}"


def test_the_connector_exposes_no_route_that_is_not_a_get():
    app = create_app(_unused, _unused, _unused, _unused, _unused)

    methods = {m for route in app.routes for m in getattr(route, "methods", set())}

    assert methods <= {"GET", "HEAD"}, f"the connector must stay read-only, but exposes {methods}"
