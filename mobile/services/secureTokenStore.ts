import type { TokenPair, TokenStore } from "@muninn/api";
import * as SecureStore from "expo-secure-store";

const KEY = "muninn.tokens";

/** Keeps the token pair in the platform keychain/keystore. */
export const secureTokenStore: TokenStore = {
  async get() {
    const raw = await SecureStore.getItemAsync(KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as TokenPair;
    } catch {
      return null;
    }
  },
  async set(tokens) {
    if (tokens) {
      await SecureStore.setItemAsync(KEY, JSON.stringify({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken }));
    } else {
      await SecureStore.deleteItemAsync(KEY);
    }
  },
};
