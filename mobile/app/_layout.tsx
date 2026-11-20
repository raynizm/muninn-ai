import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { useEffect } from "react";
import { useUploadTriggers } from "../hooks/useUploadTriggers";
import { useSession } from "../stores/session";

const queryClient = new QueryClient();

export default function RootLayout() {
  const status = useSession((s) => s.status);
  const init = useSession((s) => s.init);
  const serverUrl = useSession((s) => s.serverUrl);
  useUploadTriggers(serverUrl, status === "signedIn");

  useEffect(() => {
    void init();
  }, [init]);

  return (
    <QueryClientProvider client={queryClient}>
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Protected guard={status === "signedIn"}>
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="record" options={{ presentation: "modal", headerShown: true, title: "Record" }} />
          <Stack.Screen name="recordings/[id]" options={{ headerShown: true, title: "Recording" }} />
        </Stack.Protected>
        <Stack.Protected guard={status === "signedOut"}>
          <Stack.Screen name="login" />
        </Stack.Protected>
      </Stack>
    </QueryClientProvider>
  );
}
