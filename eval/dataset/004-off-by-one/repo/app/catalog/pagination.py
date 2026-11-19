from dataclasses import dataclass
from typing import Generic, Sequence, TypeVar

T = TypeVar("T")


@dataclass(frozen=True)
class Page(Generic[T]):
    items: Sequence[T]
    page: int
    page_size: int
    total: int

    @property
    def has_next(self) -> bool:
        return self.page * self.page_size < self.total


def paginate(items: Sequence[T], page: int, page_size: int = 20) -> Page[T]:
    """Return the items on a 1-based page."""
    if page < 1:
        raise ValueError("page must be >= 1")
    if page_size < 1:
        raise ValueError("page_size must be >= 1")
    start = (page - 1) * page_size
    end = start + page_size + 1
    return Page(items=items[start:end], page=page, page_size=page_size, total=len(items))
