import { useState, type FormEvent } from "react";
import { useApi } from "../api";
import { useAuth } from "../auth/AuthProvider";
import { confirmationProblem, passwordProblem } from "../auth/passwordRules";
import { describeError } from "../errors";

/** Change the login email and the password. Both need the current password. */
export function AccountSettings() {
  return (
    <section>
      <h2>Account</h2>
      <ChangeEmail />
      <ChangePassword />
    </section>
  );
}

const ERRORS = {
  400: "Check the new value and try again.",
  403: "Your current password is wrong.",
  409: "That email is already in use.",
};

function ChangeEmail() {
  const api = useApi();
  const { user, reloadUser } = useAuth();
  const [email, setEmail] = useState("");
  const [current, setCurrent] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await api.changeEmail(current, email.trim());
      await reloadUser();
      setEmail("");
      setCurrent("");
      setDone(true);
    } catch (err) {
      setError(describeError(err, ERRORS, "Could not change the email. Please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="provider" aria-label="Change email" onSubmit={(e) => void submit(e)}>
      <h3>Change email</h3>
      <p className="muted small">
        Signed in as {user?.email}. The email is only your login name: Muninn never sends email.
      </p>
      <label>
        New email
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="off" required />
      </label>
      <label>
        Current password
        <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
      </label>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {done && <p className="ok">Email changed.</p>}
      <div>
        <button className="primary" disabled={busy || !email.trim() || !current}>
          Save email
        </button>
      </div>
    </form>
  );
}

function ChangePassword() {
  const api = useApi();
  const [current, setCurrent] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const problem = passwordProblem(password);
  const mismatch = confirmationProblem(password, confirm);
  const ready = current && password && confirm && !problem && !mismatch;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await api.changePassword(current, password);
      setCurrent("");
      setPassword("");
      setConfirm("");
      setDone(true);
    } catch (err) {
      setError(describeError(err, ERRORS, "Could not change the password. Please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="provider" aria-label="Change password" onSubmit={(e) => void submit(e)}>
      <h3>Change password</h3>
      <label>
        Current password
        <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
      </label>
      <label>
        New password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />
      </label>
      <label>
        Confirm new password
        <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />
      </label>
      {problem && <p className="error small">{problem}</p>}
      {mismatch && <p className="error small">{mismatch}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {done && <p className="ok">Password changed. Other devices are signed out.</p>}
      <div>
        <button className="primary" disabled={busy || !ready}>
          Change password
        </button>
      </div>
    </form>
  );
}
