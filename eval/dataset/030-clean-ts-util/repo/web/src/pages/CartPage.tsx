import React from "react";
import { useCart } from "../hooks/useCart";
import { formatPrice } from "../lib/formatPrice";

export function CartPage() {
  const cart = useCart();
  return (
    <section>
      <h1>Your cart</h1>
      <p className="total">Total: {formatPrice(cart.totalCents, cart.currency, navigator.language)}</p>
    </section>
  );
}
