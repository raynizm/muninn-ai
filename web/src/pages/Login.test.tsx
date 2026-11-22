import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@muninn/api";
import { renderPage, user } from "../test-utils";
import { Login } from "./Login";

const tokenResponse = { accessToken: "A", refreshToken: "R", expiresIn: 900, user: user("ADMIN") };

function renderLogin(client = {}, signedInAs: "ADMIN" | "USER" | null = null) {
  return renderPage(
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/" element={<p>Home screen</p>} />
    </Routes>,
    client,
    { signedInAs, route: "/login" },
  );
}

describe("Login", () => {
  it("signsInWithEmailAndPassword", async () => {
    const login = vi.fn(async () => tokenResponse);
    const me = vi.fn().mockRejectedValueOnce(new Error("signed out")).mockResolvedValue(user("ADMIN"));
    renderLogin({ login, me });

    await userEvent.type(await screen.findByLabelText(/email/i), "ann@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "long enough password");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(login).toHaveBeenCalledWith("ann@example.com", "long enough password");
    expect(await screen.findByText("Home screen")).toBeInTheDocument();
  });

  it("showsAnErrorForAWrongPassword", async () => {
    const login = vi.fn().mockRejectedValue(new ApiError(401, "Unauthorized"));
    renderLogin({ login });

    await userEvent.type(await screen.findByLabelText(/email/i), "ann@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "nope nope nope");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Wrong email or password.");
  });

  it("explainsThrottlingInPlainWords", async () => {
    const login = vi.fn().mockRejectedValue(new ApiError(429, "Too Many Requests"));
    renderLogin({ login });

    await userEvent.type(await screen.findByLabelText(/email/i), "ann@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "long enough password");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/too many attempts/i);
  });

  it("saysSoWhenTheServerCannotBeReached", async () => {
    const login = vi.fn().mockRejectedValue(new TypeError("Failed to fetch"));
    renderLogin({ login });

    await userEvent.type(await screen.findByLabelText(/email/i), "ann@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "long enough password");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/cannot reach the server/i);
  });

  it("linksToPasswordReset", async () => {
    renderLogin();
    expect(await screen.findByRole("link", { name: /forgot your password/i })).toHaveAttribute("href", "/reset-password");
  });

  it("redirectsAwayWhenAlreadySignedIn", async () => {
    renderLogin({}, "ADMIN");
    expect(await screen.findByText("Home screen")).toBeInTheDocument();
  });
});
