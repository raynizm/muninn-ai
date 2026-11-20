import type { UserView } from "@muninn/api";
import * as SecureStore from "expo-secure-store";
import { create } from "zustand";
import { clientFor } from "../services/api";
import { logout, normalizeServerUrl, signIn } from "../services/auth";
import { secureTokenStore } from "../services/secureTokenStore";

const SERVER_KEY = "muninn.serverUrl";

export type SessionStatus = "loading" | "signedOut" | "signedIn";

interface SessionState {
  status: SessionStatus;
  serverUrl: string | null;
  user: UserView | null;
  /** Restores the saved server and session on app start. */
  init(): Promise<void>;
  setServerUrl(input: string): Promise<void>;
  signIn(): Promise<void>;
  signOut(): Promise<void>;
}

export const useSession = create<SessionState>((set, get) => ({
  status: "loading",
  serverUrl: null,
  user: null,

  async init() {
    const serverUrl = await SecureStore.getItemAsync(SERVER_KEY);
    if (!serverUrl) {
      set({ status: "signedOut", serverUrl: null, user: null });
      return;
    }
    try {
      if (!(await secureTokenStore.get())) throw new Error("no tokens");
      const user = await clientFor(serverUrl).me();
      set({ status: "signedIn", serverUrl, user });
    } catch {
      // Offline or signed out. A refresh failure already cleared the tokens; a network failure keeps them.
      const hasTokens = (await secureTokenStore.get()) !== null;
      set({ status: hasTokens ? "signedIn" : "signedOut", serverUrl, user: null });
    }
  },

  async setServerUrl(input) {
    const serverUrl = normalizeServerUrl(input);
    await SecureStore.setItemAsync(SERVER_KEY, serverUrl);
    set({ serverUrl });
  },

  async signIn() {
    const serverUrl = get().serverUrl;
    if (!serverUrl) throw new Error("Choose a server first.");
    const user = await signIn(serverUrl);
    set({ status: "signedIn", user });
  },

  async signOut() {
    const serverUrl = get().serverUrl;
    if (serverUrl) await logout(serverUrl).catch(() => undefined);
    set({ status: "signedOut", user: null });
  },
}));
