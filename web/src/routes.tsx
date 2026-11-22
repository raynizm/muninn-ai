import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { useApi } from "./api";
import { useAuth } from "./auth/AuthProvider";
import { Layout } from "./components/Layout";
import { CreateAccount } from "./pages/CreateAccount";
import { Login } from "./pages/Login";
import { RecordingDetail } from "./pages/RecordingDetail";
import { Recordings } from "./pages/Recordings";
import { Settings } from "./pages/Settings";
import { ResetPassword } from "./pages/ResetPassword";

/** First run: everything leads to the create-account page. Afterwards that page is gone. */
function SetupGate({ children }: { children: ReactNode }) {
  const api = useApi();
  const { pathname } = useLocation();
  const status = useQuery({ queryKey: ["setup-status"], queryFn: () => api.setupStatus(), staleTime: 30_000 });

  if (status.isPending) return <p className="center">Loading…</p>;
  if (status.isError) return <p className="center error">Cannot reach the server.</p>;

  const complete = status.data.complete;
  const onSetup = pathname === "/setup";
  if (!complete && !onSetup) return <Navigate to="/setup" replace />;
  if (complete && onSetup) return <Navigate to="/" replace />;
  return <>{children}</>;
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <p className="center">Loading…</p>;
  if (!user) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <SetupGate>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/setup" element={<CreateAccount />} />
        <Route
          element={
            <RequireAuth>
              <Layout />
            </RequireAuth>
          }
        >
          <Route path="/" element={<Recordings />} />
          <Route path="/recordings/:id" element={<RecordingDetail />} />
          <Route path="/settings" element={<Settings />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </SetupGate>
  );
}
