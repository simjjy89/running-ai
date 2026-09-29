from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from garmin_connector.api import create_app
from tests.conftest import synthetic_item


class FakeFetcher:
    def __init__(self) -> None:
        self.items = [synthetic_item(188081596), synthetic_item(188090001, "treadmill_running")]
        self.error: Exception | None = None
        self.calls: list[tuple[int, int]] = []

    def __call__(self, limit: int, start: int = 0):
        self.calls.append((limit, start))
        if self.error is not None:
            raise self.error
        return self.items[start:start + limit]


@pytest.fixture
def fetcher() -> FakeFetcher:
    return FakeFetcher()


@pytest.fixture
def client(fetcher: FakeFetcher) -> TestClient:
    return TestClient(create_app(fetcher), raise_server_exceptions=False)


def test_health_is_up_regardless_of_garmin_login(client, fetcher):
    fetcher.error = GarminConnectAuthenticationError("no tokens")
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "garmin-connector"}


def test_activities_returns_bare_json_array_with_items_untouched(client, fetcher):
    response = client.get("/activities?limit=2")

    assert response.status_code == 200
    assert response.json() == fetcher.items            # no renaming, no wrapper, no normalisation
    assert fetcher.calls == [(2, 0)]


def test_activities_default_limit_is_20(client, fetcher):
    client.get("/activities")
    assert fetcher.calls == [(20, 0)]


def test_activities_forwards_a_nonzero_start_offset(client, fetcher):
    response = client.get("/activities?start=50&limit=50")

    assert response.status_code == 200
    assert fetcher.calls == [(50, 50)]


@pytest.mark.parametrize("start", ["-1", "abc"])
def test_activities_rejects_invalid_start_without_calling_garmin(client, fetcher, start):
    response = client.get(f"/activities?start={start}")

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
    assert fetcher.calls == []


@pytest.mark.parametrize("limit", ["0", "101", "abc"])
def test_activities_rejects_invalid_limit_without_calling_garmin(client, fetcher, limit):
    response = client.get(f"/activities?limit={limit}")
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
    assert fetcher.calls == []


def _forbidden() -> GarminConnectConnectionError:
    exc = GarminConnectConnectionError("client error (403)")
    exc.response = SimpleNamespace(status_code=403, text="cf-challenge cookie=abc")
    return exc


@pytest.mark.parametrize(
    "error, status, code",
    [
        (GarminConnectAuthenticationError("Username and password are required"), 401, "GARMIN_AUTH_REQUIRED"),
        (_forbidden(), 403, "GARMIN_FORBIDDEN"),
        (GarminConnectTooManyRequestsError("Rate limit exceeded"), 429, "GARMIN_RATE_LIMITED"),
        (GarminConnectConnectionError("HTTP error"), 502, "GARMIN_UPSTREAM_ERROR"),
        (RuntimeError("token=xyz"), 500, "GARMIN_CONNECTOR_ERROR"),
    ],
)
def test_activities_maps_upstream_failures_to_error_contract(client, fetcher, error, status, code):
    fetcher.error = error

    response = client.get("/activities?limit=1")

    assert response.status_code == status
    body = response.json()
    assert body["code"] == code
    assert set(body) == {"code", "message"}
    assert "xyz" not in body["message"] and "cookie" not in body["message"]
    assert fetcher.calls == [(1, 0)]                   # exactly one upstream attempt, no automatic retry


def test_no_login_endpoint_and_no_docs(client):
    assert client.post("/login").status_code in (404, 405)
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
