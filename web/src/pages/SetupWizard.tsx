import { useCallback, useEffect, useState, type FormEvent } from "react";
import type { AiModel, ConnectionTestResult, Provider, Settings } from "@muninn/api";
import { useApi } from "../api";
import { redirectUri, useAuth } from "../auth/AuthProvider";

export type WizardStep = "token" | "sso" | "signin" | "access" | "provider" | "models" | "review";

const STEPS: { id: WizardStep; label: string }[] = [
  { id: "token", label: "Setup token" },
  { id: "sso", label: "Single sign-on" },
  { id: "signin", label: "First sign-in" },
  { id: "access", label: "Who may sign in" },
  { id: "provider", label: "AI provider" },
  { id: "models", label: "Models" },
  { id: "review", label: "Finish" },
];

const STORAGE_KEY = "muninn.setup";

interface Saved {
  token?: string;
  step?: WizardStep;
}

function loadSaved(): Saved {
  try {
    return JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? "{}") as Saved;
  } catch {
    return {};
  }
}

function save(patch: Saved) {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ ...loadSaved(), ...patch }));
  } catch {
    // ignore: the wizard just restarts at the first step after a reload
  }
}

export function SetupWizard({ initialStep }: { initialStep?: WizardStep }) {
  const [step, setStepState] = useState<WizardStep>(initialStep ?? loadSaved().step ?? "token");
  const { user, loading } = useAuth();

  const setStep = useCallback((next: WizardStep) => {
    save({ step: next });
    setStepState(next);
  }, []);

  // Back from the identity provider as the first admin: carry on with the rest of the wizard.
  useEffect(() => {
    if (!initialStep && step === "signin" && !loading && user?.role === "ADMIN") {
      setStep("access");
    }
  }, [initialStep, step, loading, user, setStep]);

  const index = STEPS.findIndex((s) => s.id === step);
  return (
    <main className="card">
      <h1>Set up Muninn</h1>
      <ol className="steps" aria-label="Setup progress">
        {STEPS.map((s, i) => (
          <li key={s.id} className={i === index ? "current" : i < index ? "done" : ""}>
            {s.label}
          </li>
        ))}
      </ol>
      {step === "token" && <TokenStep onDone={() => setStep("sso")} />}
      {step === "sso" && <SsoStep onDone={() => setStep("signin")} />}
      {step === "signin" && <SignInStep />}
      {step === "access" && <AccessStep onDone={() => setStep("provider")} />}
      {step === "provider" && <ProviderStep onDone={() => setStep("models")} />}
      {step === "models" && <ModelsStep onDone={() => setStep("review")} />}
      {step === "review" && <ReviewStep />}
    </main>
  );
}

function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong");
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, run };
}

function ErrorText({ message }: { message: string | null }) {
  return message ? (
    <p role="alert" className="error">
      {message}
    </p>
  ) : null;
}

function TokenStep({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [token, setToken] = useState("");
  const { busy, error, run } = useAction();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const res = await api.verifySetupToken(token.trim());
      if (!res.valid) throw new Error("That setup token is not valid.");
      save({ token: token.trim() });
      onDone();
    });
  };

  return (
    <form onSubmit={submit}>
      <p>
        Enter the one-time setup token the server printed on first start. Read it with{" "}
        <code>docker compose logs backend | grep "SETUP TOKEN"</code>.
      </p>
      <label>
        Setup token
        <input value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" required />
      </label>
      <ErrorText message={error} />
      <button className="primary" disabled={busy || !token.trim()}>
        Continue
      </button>
    </form>
  );
}

const PRESETS = [
  { id: "google", label: "Google Workspace", issuer: "https://accounts.google.com" },
  { id: "entra", label: "Microsoft Entra", issuer: "https://login.microsoftonline.com/{tenant-id}/v2.0" },
  { id: "custom", label: "Other OIDC provider", issuer: "" },
];

function splitDomains(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map((d) => d.trim().replace(/^@/, ""))
    .filter(Boolean);
}

function SsoStep({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [issuer, setIssuer] = useState("");
  const [clientId, setClientId] = useState("");
  const [clientSecret, setClientSecret] = useState("");
  const [domains, setDomains] = useState("");
  const [tenantId, setTenantId] = useState("");
  const { busy, error, run } = useAction();

  const choose = (preset: (typeof PRESETS)[number]) => setIssuer(preset.issuer);
  const applyTenant = (value: string) => {
    setTenantId(value);
    if (issuer.includes("login.microsoftonline.com") && value.trim()) {
      setIssuer(`https://login.microsoftonline.com/${value.trim()}/v2.0`);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const token = loadSaved().token;
      if (!token) throw new Error("The setup token is missing. Go back to the first step.");
      await api.saveSso(token, {
        issuer: issuer.trim(),
        clientId: clientId.trim(),
        clientSecret,
        allowedDomains: splitDomains(domains),
        tenantId: tenantId.trim() || null,
      });
      onDone();
    });
  };

  return (
    <form onSubmit={submit}>
      <p>
        Register Muninn as an OIDC web application with your identity provider and use this redirect URI:{" "}
        <code>{redirectUri()}</code>
      </p>
      <div className="row" role="group" aria-label="Provider presets">
        {PRESETS.map((p) => (
          <button type="button" key={p.id} onClick={() => choose(p)}>
            {p.label}
          </button>
        ))}
      </div>
      <label>
        Issuer URL
        <input value={issuer} onChange={(e) => setIssuer(e.target.value)} required />
      </label>
      <label>
        Client ID
        <input value={clientId} onChange={(e) => setClientId(e.target.value)} required />
      </label>
      <label>
        Client secret
        <input type="password" value={clientSecret} onChange={(e) => setClientSecret(e.target.value)} required />
      </label>
      <label>
        Allowed email domains
        <input
          value={domains}
          onChange={(e) => setDomains(e.target.value)}
          placeholder="yourcompany.com"
          required
        />
        <small>Required. Only these domains can sign in. Separate several with commas.</small>
      </label>
      <label>
        Microsoft tenant ID (optional)
        <input value={tenantId} onChange={(e) => applyTenant(e.target.value)} />
        <small>Pins sign-in to one Entra tenant.</small>
      </label>
      <ErrorText message={error} />
      <button className="primary" disabled={busy || splitDomains(domains).length === 0}>
        Save and continue
      </button>
    </form>
  );
}

function SignInStep() {
  const { login } = useAuth();
  const { busy, error, run } = useAction();
  return (
    <div>
      <p>Now sign in with your own account. You become the first administrator.</p>
      <ErrorText message={error} />
      <button
        className="primary"
        disabled={busy}
        onClick={() => void run(() => login({ setupToken: loadSaved().token }))}
      >
        Sign in with SSO
      </button>
    </div>
  );
}

function AccessStep({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [domains, setDomains] = useState("");
  const [override, setOverride] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const { busy, error, run } = useAction();

  useEffect(() => {
    api
      .getSettings()
      .then((s) => {
        setDomains(s.allowedDomains.join(", "));
        setOverride(s.userOverrideEnabled);
      })
      .finally(() => setLoaded(true));
  }, [api]);

  const list = splitDomains(domains);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void run(async () => {
          await api.updateSettings({ allowedDomains: list, userOverrideEnabled: override });
          onDone();
        });
      }}
    >
      <label>
        Allowed email domains
        <input value={domains} onChange={(e) => setDomains(e.target.value)} disabled={!loaded} />
        <small>Required. Without it anyone with an account at your identity provider could sign in.</small>
      </label>
      <label className="check">
        <input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} />
        Let users use their own AI provider
      </label>
      <ErrorText message={error} />
      <button className="primary" disabled={busy || !loaded || list.length === 0}>
        Continue
      </button>
    </form>
  );
}

function NonLocalWarning({ provider }: { provider: Provider }) {
  return provider.isLocal ? null : (
    <p className="warning">
      <strong>{provider.name}</strong> is not on your server. Data sent to it leaves your server.
    </p>
  );
}

interface Added {
  provider: Provider;
  test: ConnectionTestResult;
}

function ProviderStep({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [added, setAdded] = useState<Added[]>([]);
  const { busy, error, run } = useAction();

  const add = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const provider = await api.createSystemProvider({
        name: name.trim(),
        baseUrl: baseUrl.trim(),
        apiKey: apiKey || null,
        enabled: true,
      });
      const test = await api.testProvider(provider.id);
      if (test.ok) await api.discoverModels(provider.id);
      setAdded((list) => [...list, { provider, test }]);
      setName("");
      setBaseUrl("");
      setApiKey("");
    });
  };

  return (
    <div>
      <p>
        Add an OpenAI-compatible provider, for example Ollama (<code>http://ollama:11434</code>) or the bundled
        Whisper (<code>http://whisper:8000</code>). You can add several, for instance one for transcription and
        one for summaries.
      </p>
      {added.map(({ provider, test }) => (
        <div key={provider.id} className="result">
          <strong>{provider.name}</strong>
          <p className={test.ok ? "ok" : "error"}>{test.message}</p>
          <NonLocalWarning provider={provider} />
        </div>
      ))}
      <form onSubmit={add}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Base URL
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="http://ollama:11434" required />
        </label>
        <label>
          API key (optional)
          <input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} autoComplete="off" />
        </label>
        <ErrorText message={error} />
        <button disabled={busy || !name.trim() || !baseUrl.trim()}>Add provider and test</button>
      </form>
      <button className="primary" disabled={!added.some((a) => a.test.ok)} onClick={onDone}>
        Continue
      </button>
    </div>
  );
}

function ModelsStep({ onDone }: { onDone: () => void }) {
  const api = useApi();
  const [models, setModels] = useState<AiModel[]>([]);
  const [chat, setChat] = useState("");
  const [transcription, setTranscription] = useState("");
  const { busy, error, run } = useAction();

  useEffect(() => {
    void (async () => {
      const providers = (await api.listProviders()).filter((p) => p.scope === "SYSTEM");
      const lists = await Promise.all(providers.map((p) => api.listModels(p.id)));
      setModels(lists.flat().filter((m) => m.enabled));
    })();
  }, [api]);

  const withCapability = (c: string) => models.filter((m) => m.capabilities.includes(c));
  const options = (c: string) =>
    withCapability(c).map((m) => (
      <option key={m.id} value={m.id}>
        {m.modelIdentifier}
      </option>
    ));

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void run(async () => {
          await api.setDefaultModels({ chatModelId: chat, transcriptionModelId: transcription });
          onDone();
        });
      }}
    >
      <label>
        Summary (chat) model
        <select value={chat} onChange={(e) => setChat(e.target.value)} required>
          <option value="">Choose…</option>
          {options("chat")}
        </select>
      </label>
      <label>
        Transcription model
        <select value={transcription} onChange={(e) => setTranscription(e.target.value)} required>
          <option value="">Choose…</option>
          {options("transcription")}
        </select>
      </label>
      {withCapability("transcription").length === 0 && (
        <p className="warning">
          No transcription model found. For the bundled Whisper, download one first (see infrastructure/whisper), then
          go back and add the provider again.
        </p>
      )}
      <ErrorText message={error} />
      <button className="primary" disabled={busy || !chat || !transcription}>
        Continue
      </button>
    </form>
  );
}

function ReviewStep() {
  const api = useApi();
  const [settings, setSettings] = useState<Settings | null>(null);
  const { busy, error, run } = useAction();

  useEffect(() => {
    api.getSettings().then(setSettings);
  }, [api]);

  if (!settings) return <p>Loading…</p>;
  const hasDomains = settings.allowedDomains.length > 0;
  const hasModels = Boolean(settings.defaultChatModelId && settings.defaultTranscriptionModelId);

  return (
    <div>
      <dl>
        <dt>Allowed domains</dt>
        <dd>{hasDomains ? settings.allowedDomains.join(", ") : "none"}</dd>
        <dt>Sign-in provider</dt>
        <dd>{settings.issuer}</dd>
        <dt>Users may use their own provider</dt>
        <dd>{settings.userOverrideEnabled ? "yes" : "no"}</dd>
        <dt>Default models</dt>
        <dd>{hasModels ? "chat and transcription set" : "not set"}</dd>
      </dl>
      {!hasDomains && <p className="error">Add at least one allowed domain before finishing.</p>}
      {!hasModels && <p className="error">Choose default chat and transcription models before finishing.</p>}
      <ErrorText message={error} />
      <button
        className="primary"
        disabled={busy || !hasDomains || !hasModels}
        onClick={() =>
          void run(async () => {
            await api.finishSetup();
            sessionStorage.removeItem(STORAGE_KEY);
            window.location.assign("/");
          })
        }
      >
        Finish setup
      </button>
    </div>
  );
}
