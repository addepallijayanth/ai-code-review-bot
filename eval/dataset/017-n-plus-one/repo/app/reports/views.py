from datetime import date

from django.http import JsonResponse

from app.orders.models import Order


def orders_today(request):
    orders = Order.objects.filter(created_at__date=date.today()).order_by("-created_at")
    rows = []
    for order in orders:
        rows.append({
            "id": order.id,
            "customer": order.customer.name,
            "items": order.items.count(),
            "total": str(order.total),
        })
    return JsonResponse({"date": date.today().isoformat(), "orders": rows})
