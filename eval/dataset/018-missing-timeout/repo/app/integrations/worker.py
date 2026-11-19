import logging

from app.integrations.forwarder import forward_event
from app.queue import events

logger = logging.getLogger(__name__)


def run():
    """Single worker thread that drains the event queue."""
    for event in events.consume("orders"):
        if not forward_event(event):
            events.retry_later(event)
