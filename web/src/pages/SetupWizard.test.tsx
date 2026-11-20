import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthProvider";
import { describe, expect, it, vi } from "vitest";
import type { ApiClient, Provider, Settings } from "@muninn/api";
import { ApiProvider } from "../api";
import { SetupWizard } from "./SetupWizard";

const settings = (over: Partial<Settings> = {}): Settings => ({
  userOverrideEnabled: false,
  allowedDomains: [],
  defaultChatModelId: "m1",
  defaultTranscriptionModelId: "m2",
  oidcConfigured: true,
  issuer: "https://accounts.google.com",
  clientId: "cid",
  tenantId: null,
  ...over,
});

const provider = (over: Partial<Provider> = {}): Provider => ({
  id: "p1",
  scope: "SYSTEM",
  name: "OpenAI",
  baseUrl: "https://api.openai.com",
  enabled: true,
  keySet: true,
  isLocal: false,
  createdAt: "2026-01-01T00:00:00Z",
  lastTest: null,
  ...over,
});

function renderWizard(client: Partial<ApiClient>, initialStep: Parameters<typeof SetupWizard>[0]["initialStep"]) {
  return render(
    <ApiProvider client={{ me: vi.fn().mockRejectedValue(new Error("signed out")), ...client } as ApiClient}>
      <AuthProvider>
        <MemoryRouter>
          <SetupWizard initialStep={initialStep} />
        </MemoryRouter>
      </AuthProvider>
    </ApiProvider>,
  );
}

describe("SetupWizard", () => {
  it("blocksFinishWithoutAllowedDomain", async () => {
    const client = { getSettings: vi.fn(async () => settings({ allowedDomains: [] })), finishSetup: vi.fn() };
    renderWizard(client, "review");

    const finish = await screen.findByRole("button", { name: /finish setup/i });
    expect(finish).toBeDisabled();
    expect(screen.getByText(/at least one allowed domain/i)).toBeInTheDocument();
    expect(client.finishSetup).not.toHaveBeenCalled();
  });

  it("allowsFinishWhenDomainsAndModelsAreSet", async () => {
    vi.spyOn(window, "location", "get").mockReturnValue({ ...window.location, assign: vi.fn() } as unknown as Location);
    const client = {
      getSettings: vi.fn(async () => settings({ allowedDomains: ["example.com"] })),
      finishSetup: vi.fn(async () => ({ complete: true })),
    };
    renderWizard(client, "review");

    const finish = await screen.findByRole("button", { name: /finish setup/i });
    await waitFor(() => expect(finish).toBeEnabled());
    await userEvent.click(finish);
    expect(client.finishSetup).toHaveBeenCalledOnce();
  });

  it("showsConnectionTestResult", async () => {
    const client = {
      createSystemProvider: vi.fn(async () => provider()),
      testProvider: vi.fn(async () => ({ ok: true, message: "ok, 3 models" })),
      discoverModels: vi.fn(async () => []),
    };
    renderWizard(client, "provider");

    await userEvent.type(screen.getByLabelText(/name/i), "OpenAI");
    await userEvent.type(screen.getByLabelText(/base url/i), "https://api.openai.com");
    await userEvent.click(screen.getByRole("button", { name: /add provider and test/i }));

    expect(await screen.findByText("ok, 3 models")).toBeInTheDocument();
    expect(client.testProvider).toHaveBeenCalledWith("p1");
  });

  it("showsFailedConnectionTestAndWarnsWhenProviderIsNotLocal", async () => {
    const client = {
      createSystemProvider: vi.fn(async () => provider({ isLocal: false })),
      testProvider: vi.fn(async () => ({ ok: false, message: "provider answered HTTP 401" })),
      discoverModels: vi.fn(),
    };
    renderWizard(client, "provider");

    await userEvent.type(screen.getByLabelText(/name/i), "OpenAI");
    await userEvent.type(screen.getByLabelText(/base url/i), "https://api.openai.com");
    await userEvent.click(screen.getByRole("button", { name: /add provider and test/i }));

    expect(await screen.findByText("provider answered HTTP 401")).toBeInTheDocument();
    expect(screen.getByText(/leaves your server/i)).toBeInTheDocument();
  });
});
