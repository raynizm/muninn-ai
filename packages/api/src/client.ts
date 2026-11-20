import type {
  AiModel,
  AuthConfig,
  CallbackRequest,
  ConnectionTestResult,
  DefaultModels,
  Provider,
  ProviderInput,
  Recording,
  RecordingDetail,
  Settings,
  SettingsUpdate,
  SsoInput,
  SsoView,
  TokenPair,
  TokenResponse,
  TokenStore,
  UploadOptions,
} from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

interface RequestOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: BodyInit | null;
  /** Public endpoints skip the bearer header and the refresh-on-401 logic. */
  auth?: boolean;
}

export interface ApiClient {
  authConfig(): Promise<AuthConfig>;
  callback(req: CallbackRequest): Promise<TokenResponse>;
  logout(): Promise<void>;

  setupStatus(): Promise<{ complete: boolean }>;
  verifySetupToken(token: string): Promise<{ valid: boolean }>;
  saveSso(setupToken: string, sso: SsoInput): Promise<SsoView>;
  recoverSso(recoveryToken: string, sso: SsoInput): Promise<SsoView>;
  createSystemProvider(input: ProviderInput): Promise<Provider>;
  setDefaultModels(models: DefaultModels): Promise<DefaultModels>;
  finishSetup(): Promise<{ complete: boolean }>;

  listRecordings(updatedSince?: string): Promise<Recording[]>;
  getRecording(id: string): Promise<RecordingDetail>;
  uploadRecording(
    id: string,
    body: BodyInit,
    sha256: string,
    mime: string,
    options?: UploadOptions,
  ): Promise<Recording>;
  deleteRecording(id: string): Promise<void>;
  /** Audio needs the bearer header, so it is fetched as a blob rather than used as a plain URL. */
  getRecordingAudio(id: string): Promise<Blob>;

  listProviders(): Promise<Provider[]>;
  createProvider(input: ProviderInput): Promise<Provider>;
  updateProvider(id: string, input: ProviderInput): Promise<Provider>;
  deleteProvider(id: string): Promise<void>;
  testProvider(id: string): Promise<ConnectionTestResult>;
  discoverModels(providerId: string): Promise<AiModel[]>;
  listModels(providerId: string): Promise<AiModel[]>;
  updateModel(id: string, update: { capabilities?: string[]; enabled?: boolean }): Promise<AiModel>;

  getSettings(): Promise<Settings>;
  updateSettings(update: SettingsUpdate): Promise<Settings>;
}

export function createClient(
  baseUrl: string,
  tokens: TokenStore,
  fetchImpl: FetchLike = (input, init) => fetch(input, init),
): ApiClient {
  const base = baseUrl.replace(/\/+$/, "");
  let refreshing: Promise<TokenPair | null> | null = null;

  /** One refresh at a time: concurrent 401s share it. A failed refresh signs the user out. */
  function refresh(): Promise<TokenPair | null> {
    refreshing ??= (async () => {
      try {
        const current = await tokens.get();
        if (!current) {
          return null;
        }
        const res = await fetchImpl(`${base}/api/auth/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken: current.refreshToken }),
        });
        if (!res.ok) {
          await tokens.set(null);
          return null;
        }
        const body = (await res.json()) as TokenResponse;
        const next = { accessToken: body.accessToken, refreshToken: body.refreshToken };
        await tokens.set(next);
        return next;
      } finally {
        refreshing = null;
      }
    })();
    return refreshing;
  }

  async function send(path: string, opts: RequestOptions, access: string | null): Promise<Response> {
    const headers = new Headers(opts.headers);
    if (access) {
      headers.set("Authorization", `Bearer ${access}`);
    }
    return fetchImpl(`${base}${path}`, { method: opts.method ?? "GET", headers, body: opts.body ?? undefined });
  }

  async function request(path: string, opts: RequestOptions = {}): Promise<Response> {
    const auth = opts.auth ?? true;
    const current = auth ? await tokens.get() : null;
    let res = await send(path, opts, current?.accessToken ?? null);
    if (auth && res.status === 401) {
      const next = await refresh();
      if (next) {
        res = await send(path, opts, next.accessToken);
      } else {
        await tokens.set(null);
      }
    }
    if (!res.ok) {
      throw new ApiError(res.status, await errorMessage(res));
    }
    return res;
  }

  async function errorMessage(res: Response): Promise<string> {
    try {
      const body = (await res.json()) as { message?: string; error?: string };
      return body.message ?? body.error ?? res.statusText;
    } catch {
      return res.statusText;
    }
  }

  async function json<T>(path: string, opts: RequestOptions = {}): Promise<T> {
    const res = await request(path, opts);
    return (await res.json()) as T;
  }

  const jsonBody = (value: unknown): Pick<RequestOptions, "body" | "headers"> => ({
    body: JSON.stringify(value),
    headers: { "Content-Type": "application/json" },
  });

  return {
    authConfig: () => json("/api/auth/config", { auth: false }),
    async callback(req) {
      const res = await json<TokenResponse>("/api/auth/callback", { method: "POST", auth: false, ...jsonBody(req) });
      await tokens.set({ accessToken: res.accessToken, refreshToken: res.refreshToken });
      return res;
    },
    async logout() {
      const current = await tokens.get();
      if (current) {
        await request("/api/auth/logout", {
          method: "POST",
          auth: false,
          ...jsonBody({ refreshToken: current.refreshToken }),
        }).catch(() => undefined);
      }
      await tokens.set(null);
    },

    setupStatus: () => json("/api/setup/status", { auth: false }),
    verifySetupToken: (token) => json("/api/setup/verify", { method: "POST", auth: false, ...jsonBody({ token }) }),
    saveSso: (setupToken, sso) =>
      json("/api/setup/sso", {
        method: "POST",
        auth: false,
        headers: { "Content-Type": "application/json", "X-Setup-Token": setupToken },
        body: JSON.stringify(sso),
      }),
    recoverSso: (recoveryToken, sso) =>
      json("/api/setup/sso", {
        method: "PUT",
        auth: false,
        headers: { "Content-Type": "application/json", "X-Setup-Token": recoveryToken },
        body: JSON.stringify(sso),
      }),
    createSystemProvider: (input) => json("/api/setup/provider", { method: "POST", ...jsonBody(input) }),
    setDefaultModels: (models) => json("/api/setup/model", { method: "POST", ...jsonBody(models) }),
    finishSetup: () => json("/api/setup/finish", { method: "POST" }),

    listRecordings: (updatedSince) =>
      json(`/api/recordings${updatedSince ? `?updatedSince=${encodeURIComponent(updatedSince)}` : ""}`),
    getRecording: (id) => json(`/api/recordings/${id}`),
    uploadRecording(id, body, sha256, mime, options = {}) {
      const headers: Record<string, string> = { "Content-Type": mime, "X-Content-SHA256": sha256 };
      if (options.createdAt) headers["X-Recording-Created-At"] = options.createdAt;
      if (options.durationMs !== undefined) headers["X-Recording-Duration-Ms"] = String(options.durationMs);
      if (options.title) headers["X-Recording-Title"] = options.title;
      return json(`/api/recordings/${id}`, { method: "PUT", headers, body });
    },
    async deleteRecording(id) {
      await request(`/api/recordings/${id}`, { method: "DELETE" });
    },
    async getRecordingAudio(id) {
      return (await request(`/api/recordings/${id}/audio`)).blob();
    },

    listProviders: () => json("/api/providers"),
    createProvider: (input) => json("/api/providers", { method: "POST", ...jsonBody(input) }),
    updateProvider: (id, input) => json(`/api/providers/${id}`, { method: "PUT", ...jsonBody(input) }),
    async deleteProvider(id) {
      await request(`/api/providers/${id}`, { method: "DELETE" });
    },
    testProvider: (id) => json(`/api/providers/${id}/test`, { method: "POST" }),
    discoverModels: (id) => json(`/api/providers/${id}/discover`, { method: "POST" }),
    listModels: (id) => json(`/api/providers/${id}/model`),
    updateModel: (id, update) => json(`/api/model/${id}`, { method: "PUT", ...jsonBody(update) }),

    getSettings: () => json("/api/settings"),
    updateSettings: (update) => json("/api/settings", { method: "PUT", ...jsonBody(update) }),
  };
}
