import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderPage } from "./test-utils";
import { AppRoutes } from "./routes";

const setupStatus = (complete: boolean) => vi.fn(async () => ({ complete }));

describe("AppRoutes", () => {
  it.each(["/", "/login", "/settings"])("incompleteSetupShowsCreateAccountOn %s", async (route) => {
    renderPage(<AppRoutes />, { setupStatus: setupStatus(false) }, { signedInAs: null, route });
    expect(await screen.findByLabelText(/setup token/i)).toBeInTheDocument();
  });

  it("completeSetupSendsSignedOutUsersToLogin", async () => {
    renderPage(<AppRoutes />, { setupStatus: setupStatus(true) }, { signedInAs: null, route: "/" });
    expect(await screen.findByRole("button", { name: /^sign in$/i })).toBeInTheDocument();
    expect(screen.queryByLabelText(/setup token/i)).not.toBeInTheDocument();
  });

  it("completeSetupNeverShowsCreateAccount", async () => {
    renderPage(<AppRoutes />, { setupStatus: setupStatus(true) }, { signedInAs: null, route: "/setup" });
    expect(await screen.findByRole("button", { name: /^sign in$/i })).toBeInTheDocument();
    expect(screen.queryByLabelText(/setup token/i)).not.toBeInTheDocument();
  });

  it("passwordResetStaysReachableAfterSetup", async () => {
    renderPage(<AppRoutes />, { setupStatus: setupStatus(true) }, { signedInAs: null, route: "/reset-password" });
    expect(await screen.findByLabelText(/recovery token/i)).toBeInTheDocument();
  });
});
