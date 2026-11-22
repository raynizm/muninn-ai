import { describe, expect, it, vi } from "vitest";
import { createClient } from "./client";
import type { TokenPair, TokenStore } from "./types";

function memoryStore(initial: TokenPair | null): TokenStore & { current: TokenPair | null } {
  const store = {
    current: initial,
    get: async () => store.current,
    set: async (t: TokenPair | null) => {
      store.current = t;
    },
  };
  return store;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const tokenResponse = (access: string, refresh: string) => ({
  accessToken: access,
  refreshToken: refresh,
  expiresIn: 900,
  user: { id: "u1", email: "a@b.se", displayName: "A", role: "USER" },
});

describe("api client", () => {
  it("sendsBearerHeader", async () => {
    const fetchMock = vi.fn(async () => json(200, []));
    const client = createClient("https://m.example", memoryStore({ accessToken: "A1", refreshToken: "R1" }), fetchMock);
    await client.listRecordings();
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://m.example/api/recordings");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer A1");
  });

  it("refreshesOnceOn401AndRetries", async () => {
    const store = memoryStore({ accessToken: "old", refreshToken: "R1" });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(401, {}))
      .mockResolvedValueOnce(json(200, tokenResponse("new", "R2")))
      .mockResolvedValueOnce(json(200, [{ id: "r1" }]));
    const client = createClient("https://m.example", store, fetchMock);

    const result = await client.listRecordings();

    expect(result).toEqual([{ id: "r1" }]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const refreshCall = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(refreshCall[0]).toBe("https://m.example/api/auth/refresh");
    expect(JSON.parse(refreshCall[1].body as string)).toEqual({ refreshToken: "R1" });
    const retry = fetchMock.mock.calls[2] as unknown as [string, RequestInit];
    expect(new Headers(retry[1].headers).get("Authorization")).toBe("Bearer new");
    expect(store.current).toEqual({ accessToken: "new", refreshToken: "R2" });
  });

  it("clearsTokensWhenRefreshFails", async () => {
    const store = memoryStore({ accessToken: "old", refreshToken: "R1" });
    const fetchMock = vi.fn().mockResolvedValueOnce(json(401, {})).mockResolvedValueOnce(json(401, {}));
    const client = createClient("https://m.example", store, fetchMock);

    await expect(client.listRecordings()).rejects.toMatchObject({ status: 401 });
    expect(store.current).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("doesNotLoopWhenRetryIsAlso401", async () => {
    const store = memoryStore({ accessToken: "old", refreshToken: "R1" });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(401, {}))
      .mockResolvedValueOnce(json(200, tokenResponse("new", "R2")))
      .mockResolvedValueOnce(json(401, {}));
    const client = createClient("https://m.example", store, fetchMock);

    await expect(client.listRecordings()).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("sendsUploadHeadersAndRawBody", async () => {
    const fetchMock = vi.fn(async () => json(201, { id: "r1" }));
    const client = createClient("https://m.example", memoryStore({ accessToken: "A", refreshToken: "R" }), fetchMock);
    const body = new Uint8Array([1, 2, 3]);

    await client.uploadRecording("r1", body, "abc", "audio/mp4");

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://m.example/api/recordings/r1");
    expect(init.method).toBe("PUT");
    const headers = new Headers(init.headers);
    expect(headers.get("Content-Type")).toBe("audio/mp4");
    expect(headers.get("X-Content-SHA256")).toBe("abc");
    expect(init.body).toBe(body);
  });

  it("doesNotAttachBearerToPublicCalls", async () => {
    const fetchMock = vi.fn(async () => json(200, { complete: false }));
    const client = createClient("https://m.example", memoryStore({ accessToken: "A", refreshToken: "R" }), fetchMock);
    await client.setupStatus();
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(new Headers(init.headers).get("Authorization")).toBeNull();
  });

  it("meReturnsTheSignedInUser", async () => {
    const user = { id: "u1", email: "a@b.se", displayName: "A", role: "USER" };
    const fetchMock = vi.fn(async () => json(200, user));
    const client = createClient("https://m.example", memoryStore({ accessToken: "A", refreshToken: "R" }), fetchMock);
    expect(await client.me()).toEqual(user);
    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    expect(url).toBe("https://m.example/api/auth/me");
  });

  it("loginPostsCredentialsAndStoresTokens", async () => {
    const store = memoryStore(null);
    const fetchMock = vi.fn(async () => json(200, tokenResponse("A9", "R9")));
    const client = createClient("https://m.example", store, fetchMock);

    const res = await client.login("ann@example.com", "correct horse");

    expect(res.user.email).toBe("a@b.se");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://m.example/api/auth/login");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ email: "ann@example.com", password: "correct horse" });
    expect(new Headers(init.headers).get("Authorization")).toBeNull();
    expect(store.current).toEqual({ accessToken: "A9", refreshToken: "R9" });
  });

  it("createAccountSendsTheSetupTokenHeader", async () => {
    const store = memoryStore(null);
    const fetchMock = vi.fn(async () => json(201, tokenResponse("A1", "R1")));
    const client = createClient("https://m.example", store, fetchMock);

    await client.createAccount("setup-token", "ann@example.com", "correct horse");

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://m.example/api/setup/account");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("X-Setup-Token")).toBe("setup-token");
    expect(JSON.parse(init.body as string)).toEqual({ email: "ann@example.com", password: "correct horse" });
    expect(store.current).toEqual({ accessToken: "A1", refreshToken: "R1" });
  });

  it("resetPasswordSendsTheRecoveryTokenHeaderAndNoBearer", async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    const client = createClient("https://m.example", memoryStore({ accessToken: "A", refreshToken: "R" }), fetchMock);

    await client.resetPassword("recovery-token", "a brand new password");

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://m.example/api/setup/account");
    expect(init.method).toBe("PUT");
    const headers = new Headers(init.headers);
    expect(headers.get("X-Setup-Token")).toBe("recovery-token");
    expect(headers.get("Authorization")).toBeNull();
    expect(JSON.parse(init.body as string)).toEqual({ password: "a brand new password" });
  });
});
