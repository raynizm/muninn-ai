import { createClient, type TokenStore, type UserView } from "@muninn/api";
import * as Crypto from "expo-crypto";
import * as WebBrowser from "expo-web-browser";
import { secureTokenStore } from "./secureTokenStore";

/** Deep link the web app's /login/callback page hands the sign-in result back to. */
export const APP_REDIRECT = "muninn://auth/callback";

/** Accepts "muninn.example.com" or a full URL; returns an origin-style URL without a trailing slash. */
export function normalizeServerUrl(input: string): string {
  let value = input.trim();
  if (!value) throw new Error("Enter the address of your Muninn server.");
  if (!/^https?:\/\//i.test(value)) value = `https://${value}`;
  value = value.replace(/\/+$/, "");
  if (!/^https?:\/\/[^/\s]+(:\d+)?(\/.*)?$/i.test(value)) throw new Error("That is not a valid server address.");
  return value;
}

const toBase64Url = (base64: string) => base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return toBase64Url(btoa(binary));
}

async function generatePkce() {
  const verifier = bytesToBase64Url(await Crypto.getRandomBytesAsync(32));
  const challenge = toBase64Url(
    await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, verifier, {
      encoding: Crypto.CryptoEncoding.BASE64,
    }),
  );
  return { verifier, challenge };
}

function parseQuery(url: string): Record<string, string> {
  const start = url.indexOf("?");
  if (start < 0) return {};
  const query = url.slice(start + 1).split("#")[0];
  const out: Record<string, string> = {};
  for (const pair of query.split("&")) {
    if (!pair) continue;
    const [k, v = ""] = pair.split("=");
    out[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, " "));
  }
  return out;
}

/**
 * Signs in through the system browser (authorization code + PKCE).
 *
 * The identity provider redirects to the server's https `/login/callback` (so one registered redirect URI
 * serves web and phone; Google does not accept custom schemes for web clients). That page sees the `app:`
 * state prefix and bounces to {@link APP_REDIRECT}, which closes the browser session here. The backend does
 * the code exchange.
 */
export async function signIn(serverUrl: string, tokens: TokenStore = secureTokenStore): Promise<UserView> {
  const client = createClient(serverUrl, tokens);
  const config = await client.authConfig();
  const { verifier, challenge } = await generatePkce();
  const state = `app:${bytesToBase64Url(await Crypto.getRandomBytesAsync(16))}`;
  const redirectUri = `${serverUrl}/login/callback`;

  const params: Record<string, string> = {
    response_type: "code",
    client_id: config.clientId,
    redirect_uri: redirectUri,
    scope: "openid email profile",
    code_challenge: challenge,
    code_challenge_method: "S256",
    state,
  };
  const query = Object.entries(params)
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join("&");
  const separator = config.authorizationEndpoint.includes("?") ? "&" : "?";

  const result = await WebBrowser.openAuthSessionAsync(`${config.authorizationEndpoint}${separator}${query}`, APP_REDIRECT);
  if (result.type !== "success") throw new Error("Sign-in was cancelled.");

  const returned = parseQuery(result.url);
  if (returned.error) throw new Error(returned.error_description ?? returned.error);
  if (returned.state !== state) throw new Error("Sign-in could not be verified (state mismatch). Please try again.");
  if (!returned.code) throw new Error("The identity provider did not return a code.");

  await client.callback({ code: returned.code, codeVerifier: verifier, redirectUri });
  return client.me();
}

/** Revokes the refresh token on the server (best effort) and clears the stored tokens. */
export async function logout(serverUrl: string, tokens: TokenStore = secureTokenStore): Promise<void> {
  await createClient(serverUrl, tokens).logout();
}
