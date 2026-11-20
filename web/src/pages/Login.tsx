import { useEffect, useRef, useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { ApiError } from "@muninn/api";
import { useAuth } from "../auth/AuthProvider";
import { useApi } from "../api";

export function Login() {
  const { user, loading, login } = useAuth();
  const api = useApi();
  const [error, setError] = useState<string | null>(null);
  const [notConfigured, setNotConfigured] = useState(false);

  useEffect(() => {
    api.authConfig().catch((e) => {
      if (e instanceof ApiError && e.status === 404) setNotConfigured(true);
    });
  }, [api]);

  if (!loading && user) {
    return <Navigate to="/" replace />;
  }

  return (
    <main className="card narrow">
      <h1>Muninn</h1>
      <p className="muted">A memory for everything you say.</p>
      {notConfigured ? (
        <p role="alert">Single sign-on is not set up yet. An administrator has to finish the setup first.</p>
      ) : (
        <button
          className="primary"
          onClick={() => login().catch((e: Error) => setError(e.message))}
          disabled={loading}
        >
          Sign in with SSO
        </button>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </main>
  );
}

/** Target of the identity provider redirect. */
export function LoginCallback() {
  const { completeLogin } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  const returningToApp = params.get("state")?.startsWith("app:") ?? false;

  useEffect(() => {
    if (started.current) return; // StrictMode runs effects twice; the code is single use
    started.current = true;
    if (returningToApp) {
      // Phone sign-in: hand the result to the app, which exchanges the code itself.
      window.location.replace(`muninn://auth/callback?${params.toString()}`);
      return;
    }
    completeLogin(params)
      .then(() => navigate("/", { replace: true }))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "Sign-in failed"));
  }, [completeLogin, navigate, params, returningToApp]);

  return (
    <main className="card narrow">
      {error ? (
        <>
          <h1>Sign-in failed</h1>
          <p role="alert" className="error">{error}</p>
          <a href="/login">Back to sign-in</a>
        </>
      ) : returningToApp ? (
        <p>Returning to the Muninn app…</p>
      ) : (
        <p>Signing you in…</p>
      )}
    </main>
  );
}
