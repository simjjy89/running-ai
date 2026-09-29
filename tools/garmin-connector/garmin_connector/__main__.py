"""CLI: python -m garmin_connector {login|status|serve|activities}

The HTTP server binds to 127.0.0.1 only; there is no option to expose it.
"""

from __future__ import annotations

import argparse
import logging
import sys
from typing import Any

from . import __version__
from .auth import interactive_login, status
from .client import DEFAULT_TOKENSTORE, MAX_LIMIT, CachedGatewayProvider
from .errors import ConnectorError, translate

BIND_HOST = "127.0.0.1"
DEFAULT_PORT = 8765


def _mask_id(value: Any) -> str:
    text = str(value) if value is not None else ""
    if len(text) <= 3:
        return "***"
    return text[:3] + "*" * (len(text) - 3)


def _summarise(item: dict[str, Any]) -> str:
    activity_type = item.get("activityType")
    type_key = activity_type.get("typeKey") if isinstance(activity_type, dict) else activity_type
    return (
        f"id={_mask_id(item.get('activityId'))} type={type_key} "
        f"startTimeGMT={item.get('startTimeGMT')} duration={item.get('duration')} distance={item.get('distance')}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="garmin_connector", description="RunningAI Garmin connector")
    parser.add_argument("--version", action="version", version=f"garmin-connector {__version__}")
    parser.add_argument(
        "--tokenstore",
        default=DEFAULT_TOKENSTORE,
        help="token store directory used by python-garminconnect (default: ~/.garminconnect; keep it outside the repo)",
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("login", help="interactive Garmin login (email/password/MFA prompted; tokens saved by the library)")
    sub.add_parser("status", help="check the local token store with one read-only Garmin call")
    p_act = sub.add_parser("activities", help="read-only diagnostic: summarise the most recent activities (no raw JSON)")
    p_act.add_argument("--limit", type=int, default=3)
    p_srv = sub.add_parser("serve", help=f"run the localhost HTTP connector on {BIND_HOST}")
    p_srv.add_argument("--port", type=int, default=DEFAULT_PORT)
    return parser


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    args = build_parser().parse_args(argv)
    try:
        if args.command == "login":
            interactive_login(args.tokenstore)
            return 0
        if args.command == "status":
            result = status(args.tokenstore)
            for key, value in result.items():
                print(f"{key}: {value}")
            return 0 if result["tokens"] == "VALID" else 1
        if args.command == "activities":
            if not 1 <= args.limit <= MAX_LIMIT:
                print(f"--limit must be between 1 and {MAX_LIMIT}", file=sys.stderr)
                return 2
            items = CachedGatewayProvider(args.tokenstore).recent_activities(args.limit)
            print(f"count: {len(items)}")
            for item in items:
                print(_summarise(item))
            return 0
        if args.command == "serve":
            import uvicorn

            from .api import create_app

            provider = CachedGatewayProvider(args.tokenstore)
            app = create_app(provider.recent_activities)
            print(f"garmin-connector {__version__} listening on http://{BIND_HOST}:{args.port} (localhost only)")
            uvicorn.run(app, host=BIND_HOST, port=args.port, log_level="info")
            return 0
    except ConnectorError as err:
        print(f"{err.code}: {err.message}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001
        err = translate(exc)
        print(f"{err.code}: {err.message}", file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
