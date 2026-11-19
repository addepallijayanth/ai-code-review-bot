import { describe, expect, it } from "vitest";
import { formatPrice } from "./formatPrice";

describe("formatPrice", () => {
  it("formats cents as dollars", () => {
    expect(formatPrice(1999, "USD", "en-US")).toBe("$19.99");
  });

  it("respects currencies without minor units", () => {
    expect(formatPrice(500, "JPY", "ja-JP")).toBe("￥500");
  });

  it("uses the locale's separators", () => {
    // de-DE puts a no-break space before the currency sign
    expect(formatPrice(123456, "EUR", "de-DE")).toMatch(/^1\.234,56\s€$/);
  });
});
