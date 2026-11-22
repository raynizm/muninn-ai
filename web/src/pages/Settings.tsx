import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useApi } from "../api";
import { AccountSettings } from "../components/AccountSettings";
import { ProviderForm } from "../components/ProviderForm";

export function Settings() {
  return (
    <>
      <h1>Settings</h1>
      <AccountSettings />
      <Providers />
    </>
  );
}

function Providers() {
  const api = useApi();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["providers"], queryFn: () => api.listProviders() });
  const providers = (query.data ?? []).filter((p) => p.scope === "SYSTEM");
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["providers"] });
    void queryClient.invalidateQueries({ queryKey: ["provider-models"] });
  };

  return (
    <>
      <section>
        <h2>AI providers</h2>
        <p className="muted">
          Muninn uses any OpenAI-compatible service for transcription and summaries, for example Ollama or the bundled
          Whisper (<code>http://whisper:8000</code>). Add one, test the connection, then choose your default models below.
        </p>
        {query.isPending ? (
          <p>Loading…</p>
        ) : (
          providers.map((p) => <ProviderForm key={p.id} provider={p} scope="SYSTEM" onChanged={refresh} />)
        )}
        <h3>Add a provider</h3>
        <ProviderForm scope="SYSTEM" onChanged={refresh} />
      </section>
      <DefaultModels providerIds={providers.map((p) => p.id)} />
    </>
  );
}

function DefaultModels({ providerIds }: { providerIds: string[] }) {
  const api = useApi();
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: ["settings"], queryFn: () => api.getSettings() });
  const models = useQuery({
    queryKey: ["provider-models", providerIds.join(",")],
    queryFn: async () => (await Promise.all(providerIds.map((id) => api.listModels(id)))).flat().filter((m) => m.enabled),
  });

  const [chat, setChat] = useState("");
  const [transcription, setTranscription] = useState("");
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!settings.data) return;
    setChat(settings.data.defaultChatModelId ?? "");
    setTranscription(settings.data.defaultTranscriptionModelId ?? "");
  }, [settings.data]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSaved(false);
    try {
      await api.updateSettings({
        defaultChatModelId: chat || null,
        defaultTranscriptionModelId: transcription || null,
      });
      await queryClient.invalidateQueries({ queryKey: ["settings"] });
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the default models.");
    }
  };

  const options = (capability: string) =>
    (models.data ?? [])
      .filter((m) => m.capabilities.includes(capability))
      .map((m) => (
        <option key={m.id} value={m.id}>
          {m.modelIdentifier}
        </option>
      ));

  return (
    <section>
      <h2>Default models</h2>
      <form onSubmit={(e) => void submit(e)}>
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
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {saved && <p className="ok">Saved.</p>}
        <button className="primary">Save default models</button>
      </form>
    </section>
  );
}
