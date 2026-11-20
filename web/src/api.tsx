import { createContext, useContext, type ReactNode } from "react";
import { createClient, type ApiClient } from "@muninn/api";
import { localTokenStore } from "./auth/tokenStore";

/** Same origin as the page: the backend serves the SPA, and Vite proxies /api in development. */
export const defaultClient: ApiClient = createClient(window.location.origin, localTokenStore);

const ApiContext = createContext<ApiClient>(defaultClient);

export function ApiProvider({ client, children }: { client: ApiClient; children: ReactNode }) {
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  return useContext(ApiContext);
}
