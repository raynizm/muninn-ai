import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { confirmationProblem, passwordProblem } from "../auth/passwordRules";
import { describeError } from "../errors";

/** First run: the setup token from the server log plus the owner's email and password. */
export function CreateAccount() {
  const { createAccount } = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [token, setToken] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const problem = passwordProblem(password);
  const mismatch = confirmationProblem(password, confirm);
  const ready = token.trim() && email.trim() && password && confirm && !problem && !mismatch;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await createAccount(token.trim(), email.trim(), password);
      await queryClient.invalidateQueries({ queryKey: ["setup-status"] });
      navigate("/", { replace: true });
    } catch (err) {
      setError(
        describeError(
          err,
          {
            400: "Check the email and password and try again.",
            401: "That setup token is not valid.",
            404: "Setup is already finished. Sign in instead.",
            409: "An account already exists. Sign in instead.",
          },
          "Could not create the account. Please try again.",
        ),
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="card narrow">
      <h1>Welcome to Muninn</h1>
      <p className="muted">Create your account. It is the only one: Muninn is just for you.</p>
      <form onSubmit={(e) => void submit(e)}>
        <label>
          Setup token
          <input value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" required />
          <small>
            Printed once in the server log: <code>docker compose logs backend | grep "SETUP TOKEN"</code>
          </small>
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
          <small>Only used to sign in. Muninn never sends email.</small>
        </label>
        <label>
          Password
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
          Create account
        </button>
      </form>
    </main>
  );
}
