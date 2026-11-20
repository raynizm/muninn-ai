import * as Network from "expo-network";
import { useEffect } from "react";
import { AppState } from "react-native";
import { drainUploads } from "../services/uploads";

/** Drains the upload queue on app start, whenever the app returns to the foreground, and when the network is back. */
export function useUploadTriggers(serverUrl: string | null, signedIn: boolean) {
  useEffect(() => {
    if (!serverUrl || !signedIn) return;
    void drainUploads(serverUrl);

    const appState = AppState.addEventListener("change", (state) => {
      if (state === "active") void drainUploads(serverUrl);
    });
    const network = Network.addNetworkStateListener((state) => {
      if (state.isConnected && state.isInternetReachable !== false) void drainUploads(serverUrl);
    });
    return () => {
      appState.remove();
      network.remove();
    };
  }, [serverUrl, signedIn]);
}
