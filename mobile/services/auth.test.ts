import * as SecureStore from "expo-secure-store";
import * as WebBrowser from "expo-web-browser";
import { createHash } from "node:crypto";
import { logout, signIn } from "./auth";
import { secureTokenStore } from "./secureTokenStore";

jest.mock("expo-secure-store", () => {
  const store = new Map<string, string>();
  return {
    getItemAsync: jest.fn(async (k: string) => store.get(k) ?? null),
    setItemAsync: jest.fn(async (k: string, v: string) => void store.set(k, v)),
    deleteItemAsync: jest.fn(async (k: string) => void store.delete(k)),
  };
});

jest.mock("expo-web-browser", () => ({ openAuthSessionAsync: jest.fn() }));

jest.mock("expo-crypto", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const nodeCrypto = require("node:crypto");
  return {
    CryptoDigestAlgorithm: { SHA256: "SHA-256" },
    CryptoEncoding: { BASE64: "base64" },
    getRandomBytesAsync: async (n: number) => new Uint8Array(nodeCrypto.randomBytes(n)),
    digestStringAsync: async (_algorithm: string, data: string) =>
      nodeCrypto.createHash("sha256").update(data).digest("base64"),
  };
});

const SERVER = "https://muninn.example";
const openAuth = WebBrowser.openAuthSessionAsync as jest.Mock;
const user = { id: "u1", email: "ann@example.com", displayName: "Ann", role: "USER" };

let calls: { url: string; init?: RequestInit }[] = [];

function respond(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), { status });
}

beforeEach(async () => {
  calls = [];
  openAuth.mockReset();
  (SecureStore.setItemAsync as jest.Mock).mockClear();
  (SecureStore.deleteItemAsync as jest.Mock).mockClear();
  await secureTokenStore.set(null);
  (SecureStore.setItemAsync as jest.Mock).mockClear();
  (SecureStore.deleteItemAsync as jest.Mock).mockClear();
  global.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    if (url.endsWith("/api/auth/config")) {
      return respond({ issuer: "https://idp.example", clientId: "cid", authorizationEndpoint: "https://idp.example/authorize" });
    }
    if (url.endsWith("/api/auth/callback")) {
      return respond({ accessToken: "A1", refreshToken: "R1", expiresIn: 900, user });
    }
    if (url.endsWith("/api/auth/me")) return respond(user);
    if (url.endsWith("/api/auth/logout")) return respond(null, 204);
    return respond({}, 404);
  }) as unknown as typeof fetch;
});

/** Pretends to be the identity provider redirecting back to the app, echoing the state it was given. */
function browserReturns(override?: { state?: string; type?: string }) {
  openAuth.mockImplementation(async (authorizeUrl: string) => {
    const state = new URL(authorizeUrl).searchParams.get("state");
    if (override?.type) return { type: override.type };
    return { type: "success", url: `muninn://auth/callback?code=abc&state=${override?.state ?? state}` };
  });
}

describe("signIn", () => {
  it("signInStoresTokensInSecureStore", async () => {
    browserReturns();

    const result = await signIn(SERVER);

    expect(result).toEqual(user);
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
      expect.stringContaining("tokens"),
      JSON.stringify({ accessToken: "A1", refreshToken: "R1" }),
    );
    expect(await secureTokenStore.get()).toEqual({ accessToken: "A1", refreshToken: "R1" });

    const authorize = new URL(openAuth.mock.calls[0][0]);
    expect(authorize.searchParams.get("code_challenge_method")).toBe("S256");
    expect(authorize.searchParams.get("redirect_uri")).toBe(`${SERVER}/login/callback`);
    expect(authorize.searchParams.get("state")).toMatch(/^app:/);
    expect(openAuth.mock.calls[0][1]).toBe("muninn://auth/callback");

    const callback = calls.find((c) => c.url.endsWith("/api/auth/callback"))!;
    const body = JSON.parse(callback.init!.body as string);
    expect(body).toMatchObject({ code: "abc", redirectUri: `${SERVER}/login/callback` });
    const expectedChallenge = createHash("sha256").update(body.codeVerifier).digest("base64url");
    expect(authorize.searchParams.get("code_challenge")).toBe(expectedChallenge);
  });

  it("signInRejectsStateMismatch", async () => {
    browserReturns({ state: "app:evil" });

    await expect(signIn(SERVER)).rejects.toThrow(/state/i);

    expect(calls.some((c) => c.url.endsWith("/api/auth/callback"))).toBe(false);
    expect(await secureTokenStore.get()).toBeNull();
  });

  it("signInIsCancelledWhenTheBrowserIsDismissed", async () => {
    browserReturns({ type: "cancel" });
    await expect(signIn(SERVER)).rejects.toThrow(/cancel/i);
    expect(await secureTokenStore.get()).toBeNull();
  });

  it("signInSurfacesProviderErrors", async () => {
    openAuth.mockResolvedValue({ type: "success", url: "muninn://auth/callback?error=access_denied&state=x" });
    await expect(signIn(SERVER)).rejects.toThrow(/access_denied/);
  });
});

describe("logout", () => {
  it("logoutClearsTokens", async () => {
    browserReturns();
    await signIn(SERVER);

    await logout(SERVER);

    expect(await secureTokenStore.get()).toBeNull();
    expect(SecureStore.deleteItemAsync).toHaveBeenCalled();
    const call = calls.find((c) => c.url.endsWith("/api/auth/logout"))!;
    expect(JSON.parse(call.init!.body as string)).toEqual({ refreshToken: "R1" });
  });
});
