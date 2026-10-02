"""Per-activity detail parts (Phase 6H-1A): call-through, validation, error contract, no retry.

Nothing here talks to Garmin: every Garmin object is a fake. Bodies are SYNTHETIC_NOT_LIVE_GARMIN.
"""

import logging
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectNotFoundError,
    GarminConnectTooManyRequestsError,
)

from garmin_connector.api import create_app
from garmin_connector.client import (
    ACTIVITY_PARTS,
    MAX_ACTIVITY_ID,
    CachedGatewayProvider,
    GarminGateway,
    no_retry_garmin,
)
from garmin_connector.errors import (
    GARMIN_AUTH_REQUIRED,
    GARMIN_FORBIDDEN,
    GARMIN_NOT_FOUND,
    GARMIN_RATE_LIMITED,
    GARMIN_UPSTREAM_ERROR,
    ConnectorError,
)
from tests.conftest import FakeDetailGarmin, synthetic_activity_part

ACTIVITY_ID = 188081596

EXPECTED_METHODS = {
    "detail": "get_activity",
    "splits": "get_activity_splits",
    "hr-zones": "get_activity_hr_in_timezones",
    "power-zones": "get_activity_power_in_timezones",
    "samples": "get_activity_details",
}


def _http_error(status: int) -> GarminConnectConnectionError:
    exc = GarminConnectConnectionError(f"API Error {status}")
    exc.response = SimpleNamespace(status_code=status, text="secret-body-must-not-leak")
    return exc


# ---- gateway ----------------------------------------------------------------------------------


def test_every_part_maps_to_the_installed_library_method():
    assert ACTIVITY_PARTS == EXPECTED_METHODS


@pytest.mark.parametrize("part,method", sorted(EXPECTED_METHODS.items()))
def test_each_part_calls_exactly_its_library_method_once_with_a_string_id(part, method):
    garmin = FakeDetailGarmin()

    result = GarminGateway(garmin).activity_part(part, ACTIVITY_ID)

    assert garmin.part_calls == [(method, (str(ACTIVITY_ID),), {})]
    assert result == synthetic_activity_part(method)


def test_samples_forward_max_chart_only_when_asked():
    garmin = FakeDetailGarmin()
    gateway = GarminGateway(garmin)

    gateway.activity_part("samples", ACTIVITY_ID)
    gateway.activity_part("samples", ACTIVITY_ID, max_chart=10_000)

    assert garmin.part_calls == [
        ("get_activity_details", (str(ACTIVITY_ID),), {}),
        ("get_activity_details", (str(ACTIVITY_ID),), {"maxchart": 10_000}),
    ]


@pytest.mark.parametrize("bad_id", [0, -1, MAX_ACTIVITY_ID + 1, True, "188081596", 1.5])
def test_an_invalid_activity_id_is_rejected_before_any_garmin_call(bad_id):
    garmin = FakeDetailGarmin()

    with pytest.raises(ValueError):
        GarminGateway(garmin).activity_part("detail", bad_id)

    assert garmin.part_calls == []


def test_unknown_part_and_misplaced_max_chart_are_rejected():
    garmin = FakeDetailGarmin()
    gateway = GarminGateway(garmin)

    with pytest.raises(ValueError):
        gateway.activity_part("weather", ACTIVITY_ID)
    with pytest.raises(ValueError):
        gateway.activity_part("splits", ACTIVITY_ID, max_chart=100)
    with pytest.raises(ValueError):
        gateway.activity_part("samples", ACTIVITY_ID, max_chart=0)
    assert garmin.part_calls == []


@pytest.mark.parametrize("body", [{}, [], None, {"lapDTOs": []}, [{"zoneNumber": 1}]])
def test_raw_bodies_pass_through_untouched(body):
    garmin = FakeDetailGarmin()
    garmin.part_results["get_activity_splits"] = body

    assert GarminGateway(garmin).activity_part("splits", ACTIVITY_ID) == body


def test_a_non_json_container_body_is_an_upstream_error():
    garmin = FakeDetailGarmin()
    garmin.part_results["get_activity"] = "<html>"

    with pytest.raises(ConnectorError) as info:
        GarminGateway(garmin).activity_part("detail", ACTIVITY_ID)

    assert info.value.code == GARMIN_UPSTREAM_ERROR


@pytest.mark.parametrize(
    "error,code",
    [
        (GarminConnectAuthenticationError("token abc"), GARMIN_AUTH_REQUIRED),
        (GarminConnectTooManyRequestsError("slow down"), GARMIN_RATE_LIMITED),
        (GarminConnectNotFoundError("API Error 404"), GARMIN_NOT_FOUND),
        (_http_error(403), GARMIN_FORBIDDEN),
        (_http_error(404), GARMIN_NOT_FOUND),
        (_http_error(503), GARMIN_UPSTREAM_ERROR),
    ],
)
def test_library_failures_become_the_error_contract_after_a_single_attempt(error, code):
    garmin = FakeDetailGarmin()
    garmin.part_errors["get_activity_splits"] = error

    with pytest.raises(ConnectorError) as info:
        GarminGateway(garmin).activity_part("splits", ACTIVITY_ID)

    assert info.value.code == code
    assert "secret-body-must-not-leak" not in info.value.message
    assert "abc" not in info.value.message
    assert len(garmin.part_calls) == 1   # no retry in the connector


def test_the_default_garmin_factory_disables_library_retries():
    # python-garminconnect 0.3.16 defaults to retry_attempts=3; the connector must never retry.
    assert no_retry_garmin.keywords == {"retry_attempts": 0}
    assert no_retry_garmin().retry_attempts == 0


# ---- provider -----------------------------------------------------------------------------------


def test_provider_drops_the_cached_gateway_after_an_auth_failure():
    created: list[FakeDetailGarmin] = []

    def factory() -> FakeDetailGarmin:
        garmin = FakeDetailGarmin()
        if not created:
            garmin.part_errors["get_activity"] = GarminConnectAuthenticationError("expired")
        created.append(garmin)
        return garmin

    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=factory)

    with pytest.raises(ConnectorError) as info:
        provider.activity_part("detail", ACTIVITY_ID)
    assert info.value.code == GARMIN_AUTH_REQUIRED

    provider.activity_part("detail", ACTIVITY_ID)   # explicit next request reloads the token store
    assert len(created) == 2
    assert [len(g.part_calls) for g in created] == [1, 1]


def test_provider_keeps_the_gateway_after_a_rate_limit():
    created: list[FakeDetailGarmin] = []

    def factory() -> FakeDetailGarmin:
        garmin = FakeDetailGarmin()
        garmin.part_errors["get_activity_details"] = GarminConnectTooManyRequestsError("429")
        created.append(garmin)
        return garmin

    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=factory)
    for _ in range(2):
        with pytest.raises(ConnectorError):
            provider.activity_part("samples", ACTIVITY_ID)

    assert len(created) == 1
    assert len(created[0].part_calls) == 2   # one per explicit request, never more


# ---- HTTP -----------------------------------------------------------------------------------------


class FakePartFetcher:
    def __init__(self) -> None:
        self.calls: list[tuple[str, int, int | None]] = []
        self.error: Exception | None = None
        self.result: object = {"raw": True}

    def __call__(self, part: str, activity_id: int, max_chart: int | None = None):
        self.calls.append((part, activity_id, max_chart))
        if self.error is not None:
            raise self.error
        return self.result


def _unused(*_args, **_kwargs):
    raise AssertionError("not used by these tests")


@pytest.fixture
def parts() -> FakePartFetcher:
    return FakePartFetcher()


@pytest.fixture
def http(parts: FakePartFetcher) -> TestClient:
    return TestClient(create_app(_unused, _unused, _unused, parts), raise_server_exceptions=False)


@pytest.mark.parametrize("part", sorted(EXPECTED_METHODS))
def test_each_endpoint_routes_to_its_part_and_returns_the_body_unchanged(http, parts, part):
    parts.result = {"anything": [1, 2, {"nested": None}]}

    response = http.get(f"/activities/{ACTIVITY_ID}/{part}")

    assert response.status_code == 200
    assert response.json() == {"anything": [1, 2, {"nested": None}]}
    assert parts.calls == [(part, ACTIVITY_ID, None)]


def test_samples_endpoint_accepts_a_bounded_max_chart(http, parts):
    assert http.get(f"/activities/{ACTIVITY_ID}/samples?maxChart=5000").status_code == 200
    assert http.get(f"/activities/{ACTIVITY_ID}/samples?maxChart=0").status_code == 400
    assert http.get(f"/activities/{ACTIVITY_ID}/samples?maxChart=100001").status_code == 400
    assert parts.calls == [("samples", ACTIVITY_ID, 5000)]


@pytest.mark.parametrize("bad", ["0", "-5", "abc", "1.5", str(MAX_ACTIVITY_ID + 1)])
def test_an_invalid_activity_id_is_a_400_without_any_fetch(http, parts, bad):
    response = http.get(f"/activities/{bad}/splits")

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
    assert parts.calls == []


@pytest.mark.parametrize(
    "error,status,code",
    [
        (GarminConnectAuthenticationError("x"), 401, GARMIN_AUTH_REQUIRED),
        (GarminConnectTooManyRequestsError("x"), 429, GARMIN_RATE_LIMITED),
        (GarminConnectNotFoundError("x"), 404, GARMIN_NOT_FOUND),
        (_http_error(403), 403, GARMIN_FORBIDDEN),
        (_http_error(502), 502, GARMIN_UPSTREAM_ERROR),
    ],
)
def test_failures_use_the_connector_error_contract(http, parts, error, status, code):
    parts.error = error

    response = http.get(f"/activities/{ACTIVITY_ID}/detail")

    assert response.status_code == status
    assert response.json()["code"] == code
    assert len(parts.calls) == 1


def test_the_connector_does_not_log_the_activity_id(http, parts, caplog):
    caplog.set_level(logging.INFO)
    http.get(f"/activities/{ACTIVITY_ID}/hr-zones")
    parts.error = GarminConnectTooManyRequestsError("x")
    http.get(f"/activities/{ACTIVITY_ID}/hr-zones")

    connector_lines = [r.getMessage() for r in caplog.records if r.name.startswith("garmin_connector")]
    assert connector_lines   # the connector did log both requests
    assert all(str(ACTIVITY_ID) not in line for line in connector_lines)
