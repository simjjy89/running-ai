import json
import logging
from types import SimpleNamespace

import pytest
from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from garmin_connector.errors import GARMIN_AUTH_REQUIRED, GARMIN_RATE_LIMITED, ConnectorError
from garmin_connector.recovery import (
    ERROR,
    MALFORMED,
    METRICS,
    NO_DATA,
    OK,
    MalformedResponse,
    fetch_recovery,
    parse_body_battery,
    parse_hrv,
    parse_resting_heart_rate,
    parse_sleep,
    parse_stress,
)
from tests.conftest import (
    RECOVERY_DATE,
    FakeGarmin,
    synthetic_body_battery,
    synthetic_hrv,
    synthetic_sleep,
    synthetic_stress,
)

D = RECOVERY_DATE


def _upstream(status: int) -> GarminConnectConnectionError:
    exc = GarminConnectConnectionError(f"client error ({status})")
    exc.response = SimpleNamespace(status_code=status)
    return exc


# ---- normalized contract ---------------------------------------------------------------------


def test_full_day_produces_the_normalized_contract(fake_garmin):
    result = fetch_recovery(fake_garmin, D)

    assert result == {
        "date": D,
        "metrics": {
            "hrv": {"status": OK, "data": {"lastNightAvg": 52.0, "weeklyAvg": 48.5, "status": "BALANCED"}},
            "sleep": {"status": OK, "data": {"sleepTimeSeconds": 25200, "sleepScore": 84}},
            "restingHeartRate": {"status": OK, "data": {"value": 52}},
            "bodyBattery": {"status": OK, "data": {"highest": 81, "lowest": 40, "charged": 58, "drained": 32}},
            "stress": {"status": OK, "data": {"avgStressLevel": 29, "maxStressLevel": 88}},
        },
    }
    assert tuple(result["metrics"]) == METRICS


def test_each_documented_method_is_called_once_for_the_requested_day(fake_garmin):
    fetch_recovery(fake_garmin, D)

    assert fake_garmin.recovery_calls == [
        ("get_hrv_data", (D,)),
        ("get_sleep_data", (D,)),
        ("get_rhr_daily", (D, D)),
        ("get_body_battery", (D,)),
        ("get_all_day_stress", (D,)),
    ]


def test_no_time_series_profile_id_or_raw_body_leaves_the_connector(fake_garmin):
    serialized = json.dumps(fetch_recovery(fake_garmin, D))

    for leaked in ("userProfilePK", "hrvReadings", "sleepMovement", "stressValuesArray",
                   "bodyBatteryValuesArray", "1790000000000", "feedbackPhrase", "baseline"):
        assert leaked not in serialized


def test_invalid_date_is_rejected_before_any_garmin_call(fake_garmin):
    with pytest.raises(ValueError):
        fetch_recovery(fake_garmin, "2026-02-30")
    assert fake_garmin.recovery_calls == []


# ---- missing data ----------------------------------------------------------------------------


def test_hrv_204_none_and_empty_are_no_data(fake_garmin):
    for empty in (None, {}, {"hrvSummary": None}):
        fake_garmin.recovery_results["get_hrv_data"] = empty
        assert fetch_recovery(fake_garmin, D)["metrics"]["hrv"] == {"status": NO_DATA, "data": None}


def test_missing_fields_become_null_not_defaults():
    hrv = synthetic_hrv()
    del hrv["hrvSummary"]["weeklyAvg"]
    hrv["hrvSummary"]["status"] = None
    assert parse_hrv(hrv, D) == {"lastNightAvg": 52.0, "weeklyAvg": None, "status": None}

    sleep = synthetic_sleep()
    del sleep["dailySleepDTO"]["sleepScores"]
    assert parse_sleep(sleep, D) == {"sleepTimeSeconds": 25200, "sleepScore": None}

    battery = synthetic_body_battery()
    del battery[0]["bodyBatteryValuesArray"]
    assert parse_body_battery(battery, D) == {"highest": None, "lowest": None, "charged": 58, "drained": 32}

    stress = synthetic_stress()
    del stress["maxStressLevel"]
    assert parse_stress(stress, D) == {"avgStressLevel": 29, "maxStressLevel": None}


def test_a_day_without_sleep_is_no_data():
    # Garmin returns a DTO full of nulls for a night without a recorded sleep.
    assert parse_sleep({"dailySleepDTO": {"calendarDate": D, "sleepTimeSeconds": None}}, D) is None


def test_rhr_without_a_row_for_the_day_is_no_data():
    assert parse_resting_heart_rate([], D) is None
    assert parse_resting_heart_rate([{"calendarDate": "2026-10-01", "value": 50}], D) is None


def test_body_battery_without_an_entry_for_the_day_is_no_data():
    assert parse_body_battery([], D) is None


def test_negative_stress_is_a_no_reading_marker_not_a_level():
    assert parse_stress({"calendarDate": D, "avgStressLevel": -1, "maxStressLevel": -2}, D) is None


def test_body_battery_ignores_unreadable_samples():
    battery = synthetic_body_battery()
    battery[0]["bodyBatteryValuesArray"] = [[1, None], [2], "x", [3, 55], [4, True], [5, 70]]
    assert parse_body_battery(battery, D)["highest"] == 70
    assert parse_body_battery(battery, D)["lowest"] == 55


# ---- malformed responses ---------------------------------------------------------------------


@pytest.mark.parametrize(
    ("method", "payload"),
    [
        ("get_hrv_data", ["not", "an", "object"]),
        ("get_hrv_data", {"hrvSummary": "text"}),
        ("get_sleep_data", {"dailySleepDTO": 5}),
        ("get_rhr_daily", {"calendarDate": D}),
        ("get_rhr_daily", [{"calendarDate": D, "value": "52"}]),
        ("get_rhr_daily", [{"calendarDate": D, "value": 0}]),
        ("get_body_battery", {"date": D}),
        ("get_all_day_stress", "busy"),
    ],
)
def test_malformed_metric_is_flagged_and_others_still_returned(fake_garmin, method, payload):
    fake_garmin.recovery_results[method] = payload

    metrics = fetch_recovery(fake_garmin, D)["metrics"]

    statuses = {name: m["status"] for name, m in metrics.items()}
    assert MALFORMED in statuses.values()
    assert list(statuses.values()).count(OK) == 4


def test_payload_for_a_different_date_is_malformed_never_stored_under_the_wrong_day():
    with pytest.raises(MalformedResponse):
        parse_hrv(synthetic_hrv("2026-10-01"), D)
    with pytest.raises(MalformedResponse):
        parse_sleep(synthetic_sleep("2026-10-01"), D)
    with pytest.raises(MalformedResponse):
        parse_stress(synthetic_stress("2026-10-01"), D)


def test_non_numeric_bool_nan_and_negative_values_are_dropped():
    hrv = synthetic_hrv()
    hrv["hrvSummary"].update(lastNightAvg=True, weeklyAvg=float("nan"))
    assert parse_hrv(hrv, D) == {"lastNightAvg": None, "weeklyAvg": None, "status": "BALANCED"}

    sleep = synthetic_sleep()
    sleep["dailySleepDTO"]["sleepTimeSeconds"] = -5
    assert parse_sleep(sleep, D) == {"sleepTimeSeconds": None, "sleepScore": 84}


def test_unexpected_hrv_status_text_is_not_passed_through():
    hrv = synthetic_hrv()
    hrv["hrvSummary"]["status"] = "<script>alert(1)</script>"
    assert parse_hrv(hrv, D)["status"] is None


# ---- error handling --------------------------------------------------------------------------


def test_upstream_failure_of_one_metric_marks_only_that_metric(fake_garmin):
    fake_garmin.recovery_errors["get_sleep_data"] = _upstream(500)
    fake_garmin.recovery_errors["get_hrv_data"] = _upstream(404)

    metrics = fetch_recovery(fake_garmin, D)["metrics"]

    assert metrics["sleep"] == {"status": ERROR, "data": None, "error": "GARMIN_UPSTREAM_ERROR"}
    assert metrics["hrv"]["status"] == ERROR
    assert metrics["restingHeartRate"]["status"] == OK


def test_unexpected_exception_is_a_metric_error_without_its_message(fake_garmin, caplog):
    fake_garmin.recovery_errors["get_all_day_stress"] = RuntimeError("secret-token-in-message")

    with caplog.at_level(logging.INFO):
        metrics = fetch_recovery(fake_garmin, D)["metrics"]

    assert metrics["stress"] == {"status": ERROR, "data": None, "error": "GARMIN_CONNECTOR_ERROR"}
    assert "secret-token-in-message" not in caplog.text
    assert "secret-token-in-message" not in json.dumps(metrics)


def test_rate_limit_aborts_the_whole_day_without_further_calls(fake_garmin):
    fake_garmin.recovery_errors["get_sleep_data"] = GarminConnectTooManyRequestsError("429")

    with pytest.raises(ConnectorError) as info:
        fetch_recovery(fake_garmin, D)

    assert info.value.code == GARMIN_RATE_LIMITED
    # stopped at the rate-limited call: nothing after it was attempted, nothing retried
    assert [c[0] for c in fake_garmin.recovery_calls] == ["get_hrv_data", "get_sleep_data"]


def test_upstream_429_status_also_aborts(fake_garmin):
    fake_garmin.recovery_errors["get_hrv_data"] = _upstream(429)

    with pytest.raises(ConnectorError) as info:
        fetch_recovery(fake_garmin, D)

    assert info.value.code == GARMIN_RATE_LIMITED
    assert len(fake_garmin.recovery_calls) == 1


def test_authentication_failure_aborts(fake_garmin):
    fake_garmin.recovery_errors["get_hrv_data"] = GarminConnectAuthenticationError("expired")

    with pytest.raises(ConnectorError) as info:
        fetch_recovery(fake_garmin, D)

    assert info.value.code == GARMIN_AUTH_REQUIRED


def test_fake_garmin_has_every_recovery_method():
    # Guards the fake against drifting from the methods the real library exposes.
    from garminconnect import Garmin

    for method in ("get_hrv_data", "get_sleep_data", "get_rhr_daily", "get_body_battery", "get_all_day_stress"):
        assert hasattr(Garmin, method)
        assert hasattr(FakeGarmin, method)
