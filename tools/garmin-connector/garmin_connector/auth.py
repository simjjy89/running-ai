"""Interactive login and token-store status. CLI only — never exposed over HTTP.

Credentials are read from the local terminal (password via getpass, never echoed),
handed to python-garminconnect once, and not kept. Tokens are written by the
library to ``<tokenstore>/garmin_tokens.json`` (0600 inside a 0700 directory).
"""

from __future__ import annotations

import getpass
import logging
from collections.abc import Callable
from pathlib import Path
from typing import Any

from garminconnect import Garmin
from garminconnect.exceptions import GarminConnectAuthenticationError

from .client import DEFAULT_TOKENSTORE

logger = logging.getLogger(__name__)

TOKEN_FILE_NAME = "garmin_tokens.json"


def token_file(tokenstore: str = DEFAULT_TOKENSTORE) -> Path:
    return Path(tokenstore).expanduser() / TOKEN_FILE_NAME


def interactive_login(
    tokenstore: str = DEFAULT_TOKENSTORE,
    *,
    input_fn: Callable[[str], str] = input,
    getpass_fn: Callable[[str], str] = getpass.getpass,
    garmin_factory: Callable[..., Any] = Garmin,
    out: Callable[[str], None] = print,
) -> None:
    """Log in once with prompted credentials and persist tokens via the library.

    MFA: python-garminconnect calls ``prompt_mfa`` when Garmin asks for a one-time
    code; the code is read from the terminal and never stored.
    """
    email = input_fn("Garmin email: ").strip()
    if not email:
        raise ValueError("email is required")
    password = getpass_fn("Garmin password (not echoed): ")
    if not password:
        raise ValueError("password is required")

    garmin = garmin_factory(
        email=email,
        password=password,
        prompt_mfa=lambda: input_fn("Garmin MFA code: ").strip(),
    )
    # login(tokenstore) with credentials performs the SSO flow (strategy chain inside
    # the library) and dumps the resulting tokens to <tokenstore>/garmin_tokens.json.
    garmin.login(tokenstore)
    del password
    logger.info("Garmin login succeeded; tokens stored in the local token store")
    out(f"Login successful. Tokens stored under {Path(tokenstore).expanduser()} (never printed).")
    out("Next: 'python -m garmin_connector status' or 'python -m garmin_connector serve'.")


def status(
    tokenstore: str = DEFAULT_TOKENSTORE,
    *,
    garmin_factory: Callable[[], Any] = Garmin,
) -> dict[str, Any]:
    """Report whether a token store exists and (with one read-only profile call)
    whether Garmin still accepts it. No credentials are involved."""
    path = token_file(tokenstore)
    if not path.is_file():
        return {"tokens": "MISSING", "token_file": str(path)}
    garmin = garmin_factory()
    try:
        garmin.login(tokenstore)
    except GarminConnectAuthenticationError:
        return {"tokens": "INVALID_OR_EXPIRED", "token_file": str(path)}
    display_name = getattr(garmin, "display_name", None)
    return {
        "tokens": "VALID",
        "token_file": str(path),
        "display_name": _mask(display_name),
    }


def _mask(value: str | None) -> str | None:
    if not value:
        return None
    return value[:2] + "***" if len(value) > 2 else "***"
