import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api";
import { confirmationProblem, passwordProblem } from "../auth/passwordRules";
import { describeError } from "../errors";

/** Forgot the password: a recovery token printed by the server lets the owner choose a new one. */
export function ResetPassword() {
  const api = useApi();
  const [token, setToken] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const problem = passwordProblem(password);
  const mismatch = confirmationProblem(password, confirm);
  const ready = token.trim() && password && confirm && !problem && !mismatch;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.resetPassword(token.trim(), password);
      setDone(true);
    } catch (err) {
      setError(
        describeError(
          err,
          {
            400: "That password cannot be used.",
            401: "That recovery token is not valid or was already used.",
            404: "There is no account to reset yet.",
          },
          "Could not reset the password. Please try again.",
        ),
      );
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <main className="card narrow">
        <h1>Password changed</h1>
        <p>Your password was changed. You are signed out everywhere.</p>
        <Link to="/login">Sign in</Link>
      </main>
    );
  }

  return (
    <main className="card narrow">
      <h1>Reset your password</h1>
      <p className="muted">
        Restart the server with <code>--muninn.reissue-setup-token=true</code> and read the recovery token from its log:{" "}
        <code>docker compose logs backend | grep "RECOVERY TOKEN"</code>
      </p>
      <form onSubmit={(e) => void submit(e)}>
        <label>
          Recovery token
          <input value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" required />
        </label>
        <label>
          New password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />
        </label>
        <label>
          Confirm password
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />
        </label>
        {problem && <p className="error small">{problem}</p>}
        {mismatch && <p className="error small">{mismatch}</p>}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button className="primary" disabled={busy || !ready}>
          Reset password
        </button>
      </form>
      <p className="small">
        <Link to="/login">Back to sign-in</Link>
      </p>
    </main>
  );
}
