import { screen } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { renderPage } from "../test-utils";
import { LoginCallback } from "./Login";

function renderCallback(query: string, client = {}) {
  return renderPage(
    <Routes>
      <Route path="/login/callback" element={<LoginCallback />} />
    </Routes>,
    client,
    { signedInAs: null, route: `/login/callback${query}` },
  );
}

describe("LoginCallback", () => {
  it("handsAppSignInsBackToTheApp", async () => {
    const replace = vi.fn();
    vi.spyOn(window, "location", "get").mockReturnValue({ ...window.location, replace } as unknown as Location);
    const callback = vi.fn();

    renderCallback("?code=abc&state=app%3Axyz", { callback });

    expect(await screen.findByText(/returning to the muninn app/i)).toBeInTheDocument();
    expect(replace).toHaveBeenCalledWith("muninn://auth/callback?code=abc&state=app%3Axyz");
    expect(callback).not.toHaveBeenCalled(); // the app, not the browser, exchanges the code
    vi.restoreAllMocks();
  });

  it("rejectsAWebSignInWithoutAPendingLogin", async () => {
    renderCallback("?code=abc&state=web-state");
    expect(await screen.findByText(/could not be verified/i)).toBeInTheDocument();
  });
});
