from flask import Blueprint, jsonify, request

from app.catalog.pagination import paginate
from app.catalog.repository import all_products

bp = Blueprint("catalog", __name__)


@bp.get("/products")
def list_products():
    page = paginate(all_products(), int(request.args.get("page", 1)))
    return jsonify({
        "items": [p.to_dict() for p in page.items],
        "page": page.page,
        "has_next": page.has_next,
    })
