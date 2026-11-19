import re
import unicodedata

_NON_WORD = re.compile(r"[^a-z0-9]+")
MAX_SLUG_LENGTH = 80


def slugify(title: str) -> str:
    """Turn a post title into a URL slug: lowercase ASCII words joined by hyphens."""
    normalized = unicodedata.normalize("NFKD", title).encode("ascii", "ignore").decode("ascii")
    slug = _NON_WORD.sub("-", normalized.lower()).strip("-")
    if len(slug) > MAX_SLUG_LENGTH:
        slug = slug[:MAX_SLUG_LENGTH].rstrip("-")
    return slug or "post"
