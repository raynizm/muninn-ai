import { createClient, type ApiClient } from "@muninn/api";
import { fetch as expoFetch } from "expo/fetch";
import { secureTokenStore } from "./secureTokenStore";

let cached: { url: string; client: ApiClient } | null = null;

/**
 * API client for the given server; one instance per server URL so token refreshes are shared. It uses
 * expo/fetch, which can stream a `File` as the upload body instead of loading the audio into memory.
 */
export function clientFor(serverUrl: string): ApiClient {
  if (!cached || cached.url !== serverUrl) {
    cached = { url: serverUrl, client: createClient(serverUrl, secureTokenStore, expoFetch as unknown as typeof fetch) };
  }
  return cached.client;
}
