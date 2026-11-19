from app.blog.slugs import MAX_SLUG_LENGTH, slugify


def test_lowercases_and_joins_words_with_hyphens():
    assert slugify("Hello, World!") == "hello-world"


def test_strips_accents():
    assert slugify("Crème brûlée à la maison") == "creme-brulee-a-la-maison"


def test_falls_back_when_nothing_is_left():
    assert slugify("!!!") == "post"


def test_limits_length_without_a_trailing_hyphen():
    slug = slugify("word " * 40)
    assert len(slug) <= MAX_SLUG_LENGTH
    assert not slug.endswith("-")
