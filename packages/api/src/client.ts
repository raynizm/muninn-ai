import type {
  AiModel,
  AuthConfig,
  CallbackRequest,
  ConnectionTestResult,
  Provider,
  ProviderInput,
  Recording,
  RecordingDetail,
  Settings,
  Stats,
  SettingsUpdate,
  TokenPair,
  TokenResponse,
  TokenStore,
  UploadOptions,
  UserView,
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
  /** The signed-in user. */
  me(): Promise<UserView>;

  setupStatus(): Promise<{ complete: boolean }>;
  /** First run: creates the one account with the setup token and signs it in. */
  createAccount(setupToken: string, email: string, password: string): Promise<TokenResponse>;
  login(email: string, password: string): Promise<TokenResponse>;
  /** Sets a new password with a recovery token printed by the server. */
  resetPassword(recoveryToken: string, password: string): Promise<void>;

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

  /** Changes the login email; needs the current password. */
  changeEmail(currentPassword: string, email: string): Promise<UserView>;
  /** Changes the password; other sessions are signed out and this one gets fresh tokens. */
  changePassword(currentPassword: string, newPassword: string): Promise<TokenResponse>;

  getStats(timeZone: string): Promise<Stats>;

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

    me: () => json("/api/auth/me"),

    setupStatus: () => json("/api/setup/status", { auth: false }),
    async createAccount(setupToken, email, password) {
      const res = await json<TokenResponse>("/api/setup/account", {
        method: "POST",
        auth: false,
        headers: { "Content-Type": "application/json", "X-Setup-Token": setupToken },
        body: JSON.stringify({ email, password }),
      });
      await tokens.set({ accessToken: res.accessToken, refreshToken: res.refreshToken });
      return res;
    },
    async login(email, password) {
      const res = await json<TokenResponse>("/api/auth/login", {
        method: "POST",
        auth: false,
        ...jsonBody({ email, password }),
      });
      await tokens.set({ accessToken: res.accessToken, refreshToken: res.refreshToken });
      return res;
    },
    async resetPassword(recoveryToken, password) {
      await request("/api/setup/account", {
        method: "PUT",
        auth: false,
        headers: { "Content-Type": "application/json", "X-Setup-Token": recoveryToken },
        body: JSON.stringify({ password }),
      });
    },

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
    listModels: (id) => json(`/api/providers/${id}/models`),
    updateModel: (id, update) => json(`/api/models/${id}`, { method: "PUT", ...jsonBody(update) }),

    changeEmail: (currentPassword, email) =>
      json("/api/account/email", { method: "PUT", ...jsonBody({ currentPassword, email }) }),
    async changePassword(currentPassword, newPassword) {
      const res = await json<TokenResponse>("/api/account/password", {
        method: "PUT",
        ...jsonBody({ currentPassword, newPassword }),
      });
      await tokens.set({ accessToken: res.accessToken, refreshToken: res.refreshToken });
      return res;
    },

    getStats: (timeZone) => json(`/api/stats?tz=${encodeURIComponent(timeZone)}`),

    getSettings: () => json("/api/settings"),
    updateSettings: (update) => json("/api/settings", { method: "PUT", ...jsonBody(update) }),
  };
}
