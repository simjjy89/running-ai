import pytest
from garminconnect.exceptions import GarminConnectAuthenticationError

from garmin_connector.client import CachedGatewayProvider, GarminGateway, unwrap_activity_list
from garmin_connector.errors import GARMIN_AUTH_REQUIRED, GARMIN_UPSTREAM_ERROR, ConnectorError
from tests.conftest import FakeGarmin, synthetic_item


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
