import logging

import requests

from app import settings

logger = logging.getLogger(__name__)


def forward_event(event: dict) -> bool:
    """Send one order event to the partner. Returns False if the partner rejected it."""
    response = requests.post(settings.PARTNER_WEBHOOK_URL, json=event)
    if response.status_code >= 400:
        logger.warning("partner rejected event %s: HTTP %s", event["id"], response.status_code)
        return False
    return True
