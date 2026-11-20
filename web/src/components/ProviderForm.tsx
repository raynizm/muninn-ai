import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import type { AiModel, ConnectionTestResult, Provider } from "@muninn/api";
import { useApi } from "../api";

interface Props {
  /** Existing provider to edit; omit to add a new one. */
  provider?: Provider;
  scope: "USER" | "SYSTEM";
  onChanged?: () => void;
}

const CAPABILITIES = ["chat", "transcription"];

/**
 * Add or edit a provider. The stored API key is never sent to the browser, so the key field starts
 * empty; leaving it empty keeps the stored key.
 */
export function ProviderForm({ provider, scope, onChanged }: Props) {
  const api = useApi();
  const [name, setName] = useState(provider?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? "");
  const [apiKey, setApiKey] = useState("");
  const [enabled, setEnabled] = useState(provider?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [test, setTest] = useState<ConnectionTestResult | null>(null);
  const [confirming, setConfirming] = useState(false);

  const act = async (fn: () => Promise<void>) => {
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

  const save = (e: FormEvent) => {
    e.preventDefault();
    void act(async () => {
      const input = { name: name.trim(), baseUrl: baseUrl.trim(), apiKey: apiKey || null, enabled, scope };
      if (provider) {
        await api.updateProvider(provider.id, input);
      } else {
        await api.createProvider(input);
        setName("");
        setBaseUrl("");
        setEnabled(true);
      }
      setApiKey("");
      onChanged?.();
    });
  };

  return (
    <form className="provider" onSubmit={save} aria-label={provider ? `Provider ${provider.name}` : "New provider"}>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label>
        Base URL
        <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="http://ollama:11434" required />
      </label>
      <label>
        API key
        <input
          type="password"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          autoComplete="new-password"
          placeholder={provider?.keySet ? "A key is stored. Leave blank to keep it." : "Optional"}
        />
      </label>
      <label className="check">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        Enabled
      </label>
      {provider && !provider.isLocal && (
        <p className="warning">This provider is not on your server. Data sent to it leaves your server.</p>
      )}
      {error && <p role="alert" className="error">{error}</p>}
      <div className="row">
        <button className="primary" disabled={busy || !name.trim() || !baseUrl.trim()}>
          {provider ? "Save" : "Add provider"}
        </button>
        {provider && (
          <>
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                void act(async () => {
                  setTest(await api.testProvider(provider.id));
                  onChanged?.();
                })
              }
            >
              Test connection
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() =>
                void act(async () => {
                  await api.discoverModels(provider.id);
                  onChanged?.();
                })
              }
            >
              Discover models
            </button>
            {confirming ? (
              <>
                <button
                  type="button"
                  className="danger"
                  onClick={() =>
                    void act(async () => {
                      await api.deleteProvider(provider.id);
                      onChanged?.();
                    })
                  }
                >
                  Confirm delete
                </button>
                <button type="button" onClick={() => setConfirming(false)}>
                  Cancel
                </button>
              </>
            ) : (
              <button type="button" onClick={() => setConfirming(true)}>
                Delete
              </button>
            )}
          </>
        )}
      </div>
      {test && <p className={test.ok ? "ok" : "error"}>{test.message}</p>}
      {!test && provider?.lastTest && (
        <p className={provider.lastTest.ok ? "ok muted" : "error"}>Last test: {provider.lastTest.message}</p>
      )}
      {provider && <ModelList providerId={provider.id} />}
    </form>
  );
}

function ModelList({ providerId }: { providerId: string }) {
  const api = useApi();
  const queryClient = useQueryClient();
  const key = ["models", providerId];
  const models = useQuery({ queryKey: key, queryFn: () => api.listModels(providerId) });

  const update = async (m: AiModel, change: { capabilities?: string[]; enabled?: boolean }) => {
    await api.updateModel(m.id, change);
    await queryClient.invalidateQueries({ queryKey: key });
  };

  if (!models.data || models.data.length === 0) return null;
  return (
    <table className="models">
      <thead>
        <tr>
          <th>Model</th>
          <th>Use</th>
          <th>Capabilities</th>
        </tr>
      </thead>
      <tbody>
        {models.data.map((m) => (
          <tr key={m.id}>
            <td>{m.modelIdentifier}</td>
            <td>
              <input
                type="checkbox"
                aria-label={`Enable ${m.modelIdentifier}`}
                checked={m.enabled}
                onChange={(e) => void update(m, { enabled: e.target.checked })}
              />
            </td>
            <td>
              {CAPABILITIES.map((c) => (
                <label key={c} className="check">
                  <input
                    type="checkbox"
                    checked={m.capabilities.includes(c)}
                    onChange={(e) =>
                      void update(m, {
                        capabilities: e.target.checked
                          ? [...m.capabilities, c]
                          : m.capabilities.filter((x) => x !== c),
                      })
                    }
                  />
                  {c}
                </label>
              ))}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
