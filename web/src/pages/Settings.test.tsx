import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Provider, Settings as SettingsData } from "@muninn/api";
import { renderPage } from "../test-utils";
import { Settings } from "./Settings";

const provider = (over: Partial<Provider> = {}): Provider => ({
  id: "p1",
  scope: "USER",
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
  it("nonAdminDoesNotSeeAdminSettings", async () => {
    const getSettings = vi.fn(async () => settings());
    renderPage(<Settings />, { listProviders: vi.fn(async () => []), getSettings }, { signedInAs: "USER" });

    expect(await screen.findByRole("heading", { name: /your ai provider/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /administration/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/allowed email domains/i)).not.toBeInTheDocument();
    expect(getSettings).not.toHaveBeenCalled();
  });

  it("adminSeesDomainsOverrideAndDefaultModels", async () => {
    renderPage(
      <Settings />,
      {
        listProviders: vi.fn(async () => []),
        listModels: vi.fn(async () => []),
        getSettings: vi.fn(async () => settings()),
      },
      { signedInAs: "ADMIN" },
    );

    expect(await screen.findByRole("heading", { name: /administration/i })).toBeInTheDocument();
    expect(await screen.findByLabelText(/allowed email domains/i)).toHaveValue("example.com");
    expect(screen.getByLabelText(/let users use their own/i)).toBeChecked();
    expect(screen.getByLabelText(/default summary/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/default transcription/i)).toBeInTheDocument();
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
