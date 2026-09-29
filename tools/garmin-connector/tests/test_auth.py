from pathlib import Path

from garminconnect.exceptions import GarminConnectAuthenticationError

from garmin_connector.auth import interactive_login, status, token_file
from tests.conftest import FakeGarmin


def test_interactive_login_prompts_password_with_getpass_and_prints_no_secret():
    created: list[FakeGarmin] = []

    def factory(**kwargs):
        garmin = FakeGarmin(**kwargs)
        created.append(garmin)
        return garmin

    prompts: list[str] = []
    output: list[str] = []

    def fake_input(prompt: str) -> str:
        prompts.append(prompt)
        return "synthetic@example.test" if "email" in prompt else "123456"

    def fake_getpass(prompt: str) -> str:
        prompts.append("GETPASS:" + prompt)
        return "synthetic-password"

    interactive_login("/tmp/tokens", input_fn=fake_input, getpass_fn=fake_getpass, garmin_factory=factory, out=output.append)

    garmin = created[0]
    assert garmin.email == "synthetic@example.test"
    assert garmin.password == "synthetic-password"
    assert garmin.login_calls == ["/tmp/tokens"]
    # MFA callback wired to the terminal prompt, not to a stored value
    assert garmin.kwargs["prompt_mfa"]() == "123456"
    assert any(p.startswith("GETPASS:") for p in prompts)          # password via getpass, never plain input
    joined = "\n".join(output)
    assert "synthetic-password" not in joined and "synthetic@example.test" not in joined and "123456" not in joined


def test_status_reports_missing_token_file(tmp_path: Path):
    result = status(str(tmp_path), garmin_factory=lambda: FakeGarmin())
    assert result["tokens"] == "MISSING"
    assert result["token_file"] == str(token_file(str(tmp_path)))


def test_status_valid_and_invalid_tokens(tmp_path: Path):
    token_file(str(tmp_path)).write_text("{}", encoding="utf-8")   # synthetic placeholder, not a real token

    valid = status(str(tmp_path), garmin_factory=lambda: FakeGarmin())
    assert valid["tokens"] == "VALID"
    assert valid["display_name"] == "sy***"

    rejected = FakeGarmin()
    rejected.login_error = GarminConnectAuthenticationError("rejected")
    invalid = status(str(tmp_path), garmin_factory=lambda: rejected)
    assert invalid["tokens"] == "INVALID_OR_EXPIRED"
    assert rejected.login_calls == [str(tmp_path)]
