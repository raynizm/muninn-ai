import type { TokenPair, TokenStore } from "@muninn/api";

const KEY = "muninn.tokens";

/** Keeps the token pair in localStorage so a reload stays signed in. */
export const localTokenStore: TokenStore = {
  async get() {
    try {
      const raw = localStorage.getItem(KEY);
      return raw ? (JSON.parse(raw) as TokenPair) : null;
    } catch {
      return null;
    }
  },
  async set(tokens) {
    try {
      if (tokens) {
        localStorage.setItem(KEY, JSON.stringify(tokens));
      } else {
        localStorage.removeItem(KEY);
      }
    } catch {
      // storage unavailable: the session just won't survive a reload
    }
  },
};
