import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useApi } from "../api";
import { useAuth } from "../auth/AuthProvider";
import { ProviderForm } from "../components/ProviderForm";

export function Settings() {
  const { role } = useAuth();
  return (
    <>
      <h1>Settings</h1>
      <UserProviders />
      {role === "ADMIN" && <AdminSettings />}
    </>
  );
}

function useProviders() {
  const api = useApi();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["providers"], queryFn: () => api.listProviders() });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["providers"] });
  return { providers: query.data ?? [], loading: query.isPending, refresh };
}

function UserProviders() {
  const { providers, loading, refresh } = useProviders();
  const own = providers.filter((p) => p.scope === "USER");
  return (
    <section>
      <h2>Your AI provider</h2>
      <p className="muted">
        Used for your own recordings only if the administrator allows personal providers. Otherwise the server's
        default provider is used.
      </p>
      {loading ? (
        <p>Loading…</p>
      ) : (
        own.map((p) => <ProviderForm key={p.id} provider={p} scope="USER" onChanged={refresh} />)
      )}
      <h3>Add a provider</h3>
      <ProviderForm scope="USER" onChanged={refresh} />
    </section>
  );
}

function splitDomains(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map((d) => d.trim().replace(/^@/, ""))
    .filter(Boolean);
}

function AdminSettings() {
  const api = useApi();
  const queryClient = useQueryClient();
  const { providers, refresh } = useProviders();
  const system = providers.filter((p) => p.scope === "SYSTEM");

  const settings = useQuery({ queryKey: ["settings"], queryFn: () => api.getSettings() });
  const models = useQuery({
    queryKey: ["system-models", system.map((p) => p.id).join(",")],
    queryFn: async () => (await Promise.all(system.map((p) => api.listModels(p.id)))).flat().filter((m) => m.enabled),
  });

  const [domains, setDomains] = useState("");
  const [override, setOverride] = useState(false);
  const [chat, setChat] = useState("");
  const [transcription, setTranscription] = useState("");
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const s = settings.data;
    if (!s) return;
    setDomains(s.allowedDomains.join(", "));
    setOverride(s.userOverrideEnabled);
    setChat(s.defaultChatModelId ?? "");
    setTranscription(s.defaultTranscriptionModelId ?? "");
  }, [settings.data]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSaved(false);
    try {
      await api.updateSettings({
        allowedDomains: splitDomains(domains),
        userOverrideEnabled: override,
        defaultChatModelId: chat || null,
        defaultTranscriptionModelId: transcription || null,
      });
      await queryClient.invalidateQueries({ queryKey: ["settings"] });
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the settings.");
    }
  };

  const withCapability = (c: string) => (models.data ?? []).filter((m) => m.capabilities.includes(c));
  const options = (c: string) =>
    withCapability(c).map((m) => (
      <option key={m.id} value={m.id}>
        {m.modelIdentifier}
      </option>
    ));

  return (
    <section>
      <h2>Administration</h2>
      {settings.isPending ? (
        <p>Loading…</p>
      ) : (
        <form onSubmit={(e) => void submit(e)}>
          <p className="muted">
            Sign-in provider: {settings.data?.issuer ?? "not configured"}
            {settings.data?.tenantId && ` (tenant ${settings.data.tenantId})`}
          </p>
          <label>
            Allowed email domains
            <input value={domains} onChange={(e) => setDomains(e.target.value)} />
            <small>Required. Only these domains can sign in. Separate several with commas.</small>
          </label>
          <label className="check">
            <input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} />
            Let users use their own AI provider
          </label>
          <label>
            Default summary (chat) model
            <select value={chat} onChange={(e) => setChat(e.target.value)}>
              <option value="">None</option>
              {options("chat")}
            </select>
          </label>
          <label>
            Default transcription model
            <select value={transcription} onChange={(e) => setTranscription(e.target.value)}>
              <option value="">None</option>
              {options("transcription")}
            </select>
          </label>
          {error && <p role="alert" className="error">{error}</p>}
          {saved && <p className="ok">Saved.</p>}
          <button className="primary" disabled={splitDomains(domains).length === 0}>
            Save settings
          </button>
        </form>
      )}

      <h3>System providers</h3>
      {system.map((p) => (
        <ProviderForm
          key={p.id}
          provider={p}
          scope="SYSTEM"
          onChanged={() => {
            void refresh();
            void queryClient.invalidateQueries({ queryKey: ["system-models"] });
          }}
        />
      ))}
      <h4>Add a system provider</h4>
      <ProviderForm scope="SYSTEM" onChanged={refresh} />
    </section>
  );
}
