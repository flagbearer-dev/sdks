from __future__ import annotations

import logging
import sys

logger = logging.getLogger("flagbearer")


class DebugLog:
    """Diagnostics for debug=True. Never logs keys, user data, flag names or rules."""

    def __init__(self, enabled: bool):
        self.enabled = enabled
        if enabled:
            logger.setLevel(logging.DEBUG)
            if not logger.handlers:
                handler = logging.StreamHandler(sys.stderr)
                handler.setFormatter(logging.Formatter("%(message)s"))
                logger.addHandler(handler)

    def __call__(self, message: str, **details: object) -> None:
        if not self.enabled:
            return
        try:
            if details:
                # Same shape as the Node SDK's console.debug output: { version: 42 }.
                fields = ", ".join(
                    f"{key}: {'undefined' if value is None else value}"
                    for key, value in details.items()
                )
                logger.debug("[Flagbearer] %s { %s }", message, fields)
            else:
                logger.debug("[Flagbearer] %s", message)
        except Exception:  # noqa: BLE001 - diagnostics must never interrupt flag updates
            pass
