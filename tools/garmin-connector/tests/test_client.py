import pytest
from garminconnect.exceptions import GarminConnectAuthenticationError

from garmin_connector.client import CachedGatewayProvider, GarminGateway, unwrap_activity_list
from garmin_connector.errors import GARMIN_AUTH_REQUIRED, GARMIN_UPSTREAM_ERROR, ConnectorError
from tests.conftest import FakeGarmin, synthetic_item, synthetic_lactate_threshold


def test_unwrap_accepts_bare_list_and_activity_list_wrapper():
    items = [synthetic_item()]
    assert unwrap_activity_list(items) is items
    assert unwrap_activity_list({"activityList": items}) is items
    assert unwrap_activity_list(None) == []


def test_unwrap_rejects_unknown_shape():
    with pytest.raises(ConnectorError) as info:
        unwrap_activity_list({"unexpected": 1})
    assert info.value.code == GARMIN_UPSTREAM_ERROR


def test_from_tokens_uses_credential_free_client_and_tokenstore(fake_garmin):
    gateway = GarminGateway.from_tokens("~/.garminconnect", garmin_factory=lambda: fake_garmin)

    assert fake_garmin.email is None and fake_garmin.password is None
    assert fake_garmin.login_calls == ["~/.garminconnect"]
    assert gateway.display_name == "synthetic-user"


def test_recent_activities_requests_only_what_was_asked(fake_garmin):
    fake_garmin.activities_result = [synthetic_item(1), synthetic_item(2)]
    gateway = GarminGateway(fake_garmin)

    items = gateway.recent_activities(2)

    assert fake_garmin.activities_calls == [(0, 2)]
    assert items == [synthetic_item(1), synthetic_item(2)]


def test_recent_activities_forwards_a_nonzero_start_offset(fake_garmin):
    gateway = GarminGateway(fake_garmin)

    gateway.recent_activities(50, start=50)

    assert fake_garmin.activities_calls == [(50, 50)]


def test_recent_activities_validates_limit(fake_garmin):
    gateway = GarminGateway(fake_garmin)
    with pytest.raises(ValueError):
        gateway.recent_activities(0)
    with pytest.raises(ValueError):
        gateway.recent_activities(101)
    assert fake_garmin.activities_calls == []


def test_recent_activities_validates_start(fake_garmin):
    gateway = GarminGateway(fake_garmin)
    with pytest.raises(ValueError):
        gateway.recent_activities(10, start=-1)
    assert fake_garmin.activities_calls == []


def test_provider_translates_missing_tokens_and_never_logs_in_with_credentials():
    fake = FakeGarmin()
    fake.login_error = GarminConnectAuthenticationError("Username and password are required")
    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=lambda: fake)

    with pytest.raises(ConnectorError) as info:
        provider.recent_activities(1)

    assert info.value.code == GARMIN_AUTH_REQUIRED
    assert fake.login_calls == ["~/.garminconnect"]   # exactly one token-store load, no retry


def test_lactate_threshold_returns_raw_dict_untouched(fake_garmin):
    fake_garmin.lactate_threshold_result = synthetic_lactate_threshold(heart_rate=180, speed=0.34444348)
    gateway = GarminGateway(fake_garmin)

    result = gateway.lactate_threshold()

    assert result == fake_garmin.lactate_threshold_result
    assert fake_garmin.lactate_threshold_calls == 1


def test_lactate_threshold_translates_upstream_failures(fake_garmin):
    fake_garmin.lactate_threshold_error = GarminConnectAuthenticationError("expired")
    gateway = GarminGateway(fake_garmin)

    with pytest.raises(ConnectorError) as info:
        gateway.lactate_threshold()
    assert info.value.code == GARMIN_AUTH_REQUIRED


def test_lactate_threshold_rejects_unexpected_shape(fake_garmin):
    fake_garmin.lactate_threshold_result = ["not", "a", "dict"]
    gateway = GarminGateway(fake_garmin)

    with pytest.raises(ConnectorError) as info:
        gateway.lactate_threshold()
    assert info.value.code == GARMIN_UPSTREAM_ERROR


def test_provider_lactate_threshold_caches_gateway_and_drops_it_on_auth_failure():
    fake = FakeGarmin()
    created = []

    def factory():
        created.append(fake)
        return fake

    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=factory)
    provider.lactate_threshold()
    provider.lactate_threshold()
    assert len(created) == 1

    fake.lactate_threshold_error = GarminConnectAuthenticationError("expired")
    with pytest.raises(ConnectorError):
        provider.lactate_threshold()
    fake.lactate_threshold_error = None
    provider.lactate_threshold()
    assert len(created) == 2   # reloaded the token store once, not a credential login


def test_provider_caches_gateway_and_drops_it_on_auth_failure():
    fake = FakeGarmin()
    created = []

    def factory():
        created.append(fake)
        return fake

    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=factory)
    provider.recent_activities(1)
    provider.recent_activities(1)
    assert len(created) == 1

    fake.activities_error = GarminConnectAuthenticationError("expired")
    with pytest.raises(ConnectorError):
        provider.recent_activities(1)
    fake.activities_error = None
    provider.recent_activities(1)
    assert len(created) == 2   # reloaded the token store once, not a credential login


def test_gateway_recovery_projects_the_documented_fields(fake_garmin):
    result = GarminGateway(fake_garmin).recovery("2026-10-02")

    assert result["date"] == "2026-10-02"
    assert result["metrics"]["restingHeartRate"] == {"status": "OK", "data": {"value": 52}}


def test_provider_recovery_drops_the_gateway_on_auth_failure():
    garmins: list[FakeGarmin] = []

    def factory() -> FakeGarmin:
        garmin = FakeGarmin()
        garmins.append(garmin)
        return garmin

    provider = CachedGatewayProvider("~/.garminconnect", garmin_factory=factory)
    provider.recovery("2026-10-02")
    garmins[0].recovery_errors["get_hrv_data"] = GarminConnectAuthenticationError("expired")

    with pytest.raises(ConnectorError) as info:
        provider.recovery("2026-10-02")
    assert info.value.code == GARMIN_AUTH_REQUIRED

    provider.recovery("2026-10-02")
    assert len(garmins) == 2
