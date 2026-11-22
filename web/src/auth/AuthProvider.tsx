import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Role, UserView } from "@muninn/api";
import { useApi } from "../api";

export interface AuthState {
  user: UserView | null;
  role: Role | null;
  loading: boolean;
  loginWithPassword(email: string, password: string): Promise<UserView>;
  /** First run: creates the account with the setup token and signs in. */
  createAccount(setupToken: string, email: string, password: string): Promise<UserView>;
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

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

  const loginWithPassword = useCallback(
    async (email: string, password: string) => {
      await api.login(email, password);
      const me = await api.me();
      setUser(me);
      return me;
    },
    [api],
  );

  const createAccount = useCallback(
    async (setupToken: string, email: string, password: string) => {
      await api.createAccount(setupToken, email, password);
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
    () => ({ user, role: user?.role ?? null, loading, loginWithPassword, createAccount, logout }),
    [user, loading, loginWithPassword, createAccount, logout],
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
