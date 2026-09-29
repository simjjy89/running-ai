from types import SimpleNamespace

from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from garmin_connector.errors import (
    GARMIN_AUTH_REQUIRED,
    GARMIN_CONNECTOR_ERROR,
    GARMIN_FORBIDDEN,
    GARMIN_RATE_LIMITED,
    GARMIN_UPSTREAM_ERROR,
    ConnectorError,
    translate,
)


def _connection_error(status: int | None) -> GarminConnectConnectionError:
    exc = GarminConnectConnectionError("client error")
    if status is not None:
        exc.response = SimpleNamespace(status_code=status, text="secret-body-must-not-leak")
    return exc


def test_authentication_error_maps_to_401():
    err = translate(GarminConnectAuthenticationError("Authentication failed: token abc"))
    assert (err.code, err.http_status) == (GARMIN_AUTH_REQUIRED, 401)
    assert "abc" not in err.message


def test_rate_limit_maps_to_429():
    err = translate(GarminConnectTooManyRequestsError("Rate limit exceeded"))
    assert (err.code, err.http_status) == (GARMIN_RATE_LIMITED, 429)


def test_forbidden_response_maps_to_403():
    err = translate(_connection_error(403))
    assert (err.code, err.http_status) == (GARMIN_FORBIDDEN, 403)
    assert "secret-body" not in err.message


def test_other_http_failures_map_to_502():
    for status in (500, 503, None):
        err = translate(_connection_error(status))
        assert (err.code, err.http_status) == (GARMIN_UPSTREAM_ERROR, 502)


def test_unexpected_exception_maps_to_500_without_details():
    err = translate(RuntimeError("password=hunter2"))
    assert (err.code, err.http_status) == (GARMIN_CONNECTOR_ERROR, 500)
    assert "hunter2" not in err.message


def test_connector_error_passes_through():
    original = ConnectorError(GARMIN_RATE_LIMITED, "x")
    assert translate(original) is original
    assert original.to_dict() == {"code": GARMIN_RATE_LIMITED, "message": "x"}
