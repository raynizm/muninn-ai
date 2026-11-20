import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Role, UserView } from "@muninn/api";
import { useApi } from "../api";
import { generatePkce, randomString } from "./pkce";

const OIDC_KEY = "muninn.oidc";

interface PendingLogin {
  verifier: string;
  state: string;
  setupToken?: string;
}

export interface AuthState {
  user: UserView | null;
  role: Role | null;
  loading: boolean;
  /** Redirects to the identity provider. A setup token makes this the first-admin sign-in. */
  login(options?: { setupToken?: string }): Promise<void>;
  /** Finishes the redirect: exchanges the code and loads the user. */
  completeLogin(params: URLSearchParams): Promise<UserView>;
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export const redirectUri = () => `${window.location.origin}/login/callback`;

export function AuthProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [user, setUser] = useState<UserView | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api
      .me()
      .then((u) => !cancelled && setUser(u))
      .catch(() => !cancelled && setUser(null))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [api]);

  const login = useCallback(
    async (options?: { setupToken?: string }) => {
      const config = await api.authConfig();
      const { verifier, challenge } = await generatePkce();
      const state = randomString(16);
      const pending: PendingLogin = { verifier, state, setupToken: options?.setupToken };
      sessionStorage.setItem(OIDC_KEY, JSON.stringify(pending));
      const url = new URL(config.authorizationEndpoint);
      url.searchParams.set("response_type", "code");
      url.searchParams.set("client_id", config.clientId);
      url.searchParams.set("redirect_uri", redirectUri());
      url.searchParams.set("scope", "openid email profile");
      url.searchParams.set("code_challenge", challenge);
      url.searchParams.set("code_challenge_method", "S256");
      url.searchParams.set("state", state);
      window.location.assign(url.toString());
    },
    [api],
  );

  const completeLogin = useCallback(
    async (params: URLSearchParams) => {
      const raw = sessionStorage.getItem(OIDC_KEY);
      sessionStorage.removeItem(OIDC_KEY);
      const pending = raw ? (JSON.parse(raw) as PendingLogin) : null;
      const error = params.get("error");
      if (error) {
        throw new Error(params.get("error_description") ?? error);
      }
      const code = params.get("code");
      if (!pending || !code || params.get("state") !== pending.state) {
        throw new Error("Sign-in could not be verified. Please try again.");
      }
      await api.callback({
        code,
        codeVerifier: pending.verifier,
        redirectUri: redirectUri(),
        setupToken: pending.setupToken,
      });
      const me = await api.me();
      setUser(me);
      return me;
    },
    [api],
  );

  const logout = useCallback(async () => {
    await api.logout();
    setUser(null);
  }, [api]);

  const value = useMemo<AuthState>(
    () => ({ user, role: user?.role ?? null, loading, login, completeLogin, logout }),
    [user, loading, login, completeLogin, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return ctx;
}
