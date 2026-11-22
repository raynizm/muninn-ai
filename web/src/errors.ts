import { ApiError } from "@muninn/api";

/**
 * Plain-words text for a failed request. The server only sends an HTTP status (no message body), so
 * each page says what that status means for what the user just tried.
 */
export function describeError(err: unknown, byStatus: Record<number, string>, fallback: string): string {
  if (err instanceof ApiError) {
    if (byStatus[err.status]) return byStatus[err.status];
    if (err.status === 429) return "Too many attempts. Please wait 15 minutes and try again.";
    return fallback;
  }
  if (err instanceof TypeError) return "Cannot reach the server. Check your connection and try again.";
  return fallback;
}
