/**
 * True only for URLs that are safe to place in an `href`.
 * Product/manual links come from LLM-extracted page content, so `javascript:`
 * and `data:` payloads have to be rejected before they reach the DOM.
 */
export function isSafeExternalUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}
