const MIN_CHARS = 10;
const MAX_BYTES = 72;

/** Why this password cannot be used, or null when it is fine. Mirrors the server's rules. */
export function passwordProblem(password: string): string | null {
  if (password.length > 0 && password.length < MIN_CHARS) {
    return `The password must be at least ${MIN_CHARS} characters.`;
  }
  if (new TextEncoder().encode(password).length > MAX_BYTES) {
    return `The password can be at most ${MAX_BYTES} bytes long.`;
  }
  return null;
}

/** Message for a confirmation that differs from the password, or null. */
export function confirmationProblem(password: string, confirm: string): string | null {
  return confirm.length > 0 && confirm !== password ? "The passwords do not match." : null;
}
