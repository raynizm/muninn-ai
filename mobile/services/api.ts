import { createClient, type ApiClient } from "@muninn/api";
import { secureTokenStore } from "./secureTokenStore";

let cached: { url: string; client: ApiClient } | null = null;

/** API client for the given server; one instance per server URL so token refreshes are shared. */
export function clientFor(serverUrl: string): ApiClient {
  if (!cached || cached.url !== serverUrl) {
    cached = { url: serverUrl, client: createClient(serverUrl, secureTokenStore) };
  }
  return cached.client;
}
