import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Provider, Settings as SettingsData } from "@muninn/api";
import { renderPage } from "../test-utils";
import { Settings } from "./Settings";

const provider = (over: Partial<Provider> = {}): Provider => ({
  id: "p1",
  scope: "SYSTEM",
  name: "My Ollama",
  baseUrl: "http://ollama:11434",
  enabled: true,
  keySet: true,
  isLocal: true,
  createdAt: "2026-01-01T00:00:00Z",
  lastTest: null,
  ...over,
});

const settings = (): SettingsData => ({
  userOverrideEnabled: true,
  allowedDomains: ["example.com"],
  defaultChatModelId: null,
  defaultTranscriptionModelId: null,
  oidcConfigured: true,
  issuer: "https://accounts.google.com",
  clientId: "cid",
  tenantId: null,
});

describe("Settings", () => {
  it("showsProvidersAndDefaultModelsWithoutAdminWording", async () => {
    renderPage(
      <Settings />,
      {
        listProviders: vi.fn(async () => [provider()]),
        listModels: vi.fn(async () => []),
        getSettings: vi.fn(async () => settings()),
      },
      { signedInAs: "ADMIN" },
    );

    expect(await screen.findByRole("heading", { name: /^ai providers$/i })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /^default models$/i })).toBeInTheDocument();
    expect(await screen.findByLabelText(/default summary/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/default transcription/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /administration/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/allowed email domains/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/let users use their own/i)).not.toBeInTheDocument();
  });

  it("savesTheDefaultModels", async () => {
    const updateSettings = vi.fn(async () => settings());
    renderPage(
      <Settings />,
      {
        listProviders: vi.fn(async () => [provider()]),
        listModels: vi.fn(async () => [
          { id: "m-chat", providerId: "p1", modelIdentifier: "llama3", capabilities: ["chat"], enabled: true },
          { id: "m-stt", providerId: "p1", modelIdentifier: "whisper-small", capabilities: ["transcription"], enabled: true },
        ]),
        getSettings: vi.fn(async () => settings()),
        updateSettings,
      },
      { signedInAs: "ADMIN" },
    );

    await screen.findByRole("option", { name: "llama3" }); // the model list loads after the form shows
    await userEvent.selectOptions(await screen.findByLabelText(/default summary/i), "m-chat");
    await userEvent.selectOptions(screen.getByLabelText(/default transcription/i), "m-stt");
    await userEvent.click(screen.getByRole("button", { name: /save default models/i }));

    expect(updateSettings).toHaveBeenCalledWith({ defaultChatModelId: "m-chat", defaultTranscriptionModelId: "m-stt" });
  });

  it("providerFormNeverPrefillsKey", async () => {
    renderPage(
      <Settings />,
      { listProviders: vi.fn(async () => [provider({ keySet: true })]), listModels: vi.fn(async () => []) },
      { signedInAs: "USER" },
    );

    const form = await screen.findByRole("form", { name: "Provider My Ollama" });
    const key = within(form).getByLabelText(/api key/i);
    expect(key).toHaveValue("");
    expect(key).toHaveAttribute("placeholder", expect.stringMatching(/key is stored/i));
  });

  it("savingWithBlankKeyKeepsStoredKey", async () => {
    const updateProvider = vi.fn(async () => provider());
    renderPage(
      <Settings />,
      {
        listProviders: vi.fn(async () => [provider()]),
        listModels: vi.fn(async () => []),
        updateProvider,
      },
      { signedInAs: "USER" },
    );

    const form = await screen.findByRole("form", { name: "Provider My Ollama" });
    await userEvent.click(within(form).getByRole("button", { name: /^save$/i }));

    expect(updateProvider).toHaveBeenCalledOnce();
    const [, input] = updateProvider.mock.calls[0] as unknown as [string, { apiKey?: string | null }];
    expect(input.apiKey ?? null).toBeNull();
  });

  it("warnsWhenProviderIsNotLocal", async () => {
    renderPage(
      <Settings />,
      {
        listProviders: vi.fn(async () => [provider({ isLocal: false, name: "OpenAI" })]),
        listModels: vi.fn(async () => []),
      },
      { signedInAs: "USER" },
    );
    expect(await screen.findByText(/leaves your server/i)).toBeInTheDocument();
  });
});
