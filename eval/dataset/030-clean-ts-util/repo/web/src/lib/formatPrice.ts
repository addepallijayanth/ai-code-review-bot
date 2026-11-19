const formatters = new Map<string, Intl.NumberFormat>();

/**
 * Formats an amount in minor units (cents) for display, e.g. formatPrice(1999, "USD", "en-US") -> "$19.99".
 */
export function formatPrice(amountMinor: number, currency: string, locale: string): string {
  const key = `${locale}|${currency}`;
  let formatter = formatters.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, { style: "currency", currency });
    formatters.set(key, formatter);
  }
  const digits = formatter.resolvedOptions().maximumFractionDigits ?? 2;
  return formatter.format(amountMinor / 10 ** digits);
}
