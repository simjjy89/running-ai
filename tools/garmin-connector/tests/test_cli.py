from garmin_connector.__main__ import _mask_id, _summarise, build_parser
from tests.conftest import synthetic_item


def test_activity_id_is_masked():
    assert _mask_id(188081596) == "188******"
    assert _mask_id(None) == "***"


def test_summary_line_contains_no_full_id_and_no_raw_json():
    line = _summarise(synthetic_item())
    assert "188081596" not in line
    assert "type=running" in line
    assert "startTimeGMT=2026-09-28 21:30:00" in line
    assert "duration=3600.0" in line and "distance=10000.0" in line
    assert "averageHR" not in line                      # summary only, not the raw item


def test_serve_has_no_host_option_only_port():
    parser = build_parser()
    args = parser.parse_args(["serve", "--port", "9000"])
    assert args.port == 9000
    assert not hasattr(args, "host")
