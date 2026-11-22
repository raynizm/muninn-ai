import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderPage } from "../test-utils";
import { ResetPassword } from "./ResetPassword";

async function fill(token: string, password: string, confirm = password) {
  await userEvent.type(screen.getByLabelText(/recovery token/i), token);
  await userEvent.type(screen.getByLabelText(/^new password/i), password);
  await userEvent.type(screen.getByLabelText(/confirm password/i), confirm);
}

describe("ResetPassword", () => {
  it("resetsWithTheTypedValues", async () => {
    const resetPassword = vi.fn(async () => undefined);
    renderPage(<ResetPassword />, { resetPassword }, { signedInAs: null });

    await fill("recovery-1", "a brand new password");
    await userEvent.click(screen.getByRole("button", { name: /reset password/i }));

    expect(resetPassword).toHaveBeenCalledWith("recovery-1", "a brand new password");
  });

  it("blocksMismatchedPasswords", async () => {
    renderPage(<ResetPassword />, {}, { signedInAs: null });
    await fill("recovery-1", "a brand new password", "something else entirely");
    expect(screen.getByRole("button", { name: /reset password/i })).toBeDisabled();
    expect(screen.getByText(/do not match/i)).toBeInTheDocument();
  });

  it("showsASuccessMessageWithALoginLink", async () => {
    renderPage(<ResetPassword />, { resetPassword: vi.fn(async () => undefined) }, { signedInAs: null });
    await fill("recovery-1", "a brand new password");
    await userEvent.click(screen.getByRole("button", { name: /reset password/i }));

    expect(await screen.findByText(/password was changed/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/login");
  });
});
