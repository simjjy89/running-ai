"""RunningAI Garmin connector.

Owns Garmin Connect authentication, the local token store and read-only transport.
It never touches the RunningAI database and never normalises Garmin payloads; the
Spring server does that.
"""

__version__ = "0.1.0"
