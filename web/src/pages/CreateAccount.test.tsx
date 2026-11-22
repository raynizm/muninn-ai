import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@muninn/api";
import { renderPage, user } from "../test-utils";
import { CreateAccount } from "./CreateAccount";

const tokenResponse = { accessToken: "A", refreshToken: "R", expiresIn: 900, user: user("ADMIN") };

async function fill(token: string, email: string, password: string, confirm = password) {
  await userEvent.type(screen.getByLabelText(/setup token/i), token);
  await userEvent.type(screen.getByLabelText(/^email/i), email);
  await userEvent.type(screen.getByLabelText(/^password/i), password);
  await userEvent.type(screen.getByLabelText(/confirm password/i), confirm);
}

describe("CreateAccount", () => {
  it("blocksSubmitWhenThePasswordIsTooShortOrDoesNotMatch", async () => {
    renderPage(<CreateAccount />, {}, { signedInAs: null });
    const button = screen.getByRole("button", { name: /create account/i });

    await fill("tok", "ann@example.com", "short");
    expect(button).toBeDisabled();
    expect(screen.getByText(/at least 10 characters/i)).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText(/^password/i));
    await userEvent.type(screen.getByLabelText(/^password/i), "long enough password");
    expect(button).toBeDisabled();
    expect(screen.getByText(/do not match/i)).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText(/confirm password/i));
    await userEvent.type(screen.getByLabelText(/confirm password/i), "long enough password");
    expect(button).toBeEnabled();
  });

  it("createsTheAccountWithTheTypedValues", async () => {
    const createAccount = vi.fn(async () => tokenResponse);
    renderPage(<CreateAccount />, { createAccount, setupStatus: vi.fn(async () => ({ complete: true })) }, { signedInAs: null });

    await fill("the-token", "ann@example.com", "long enough password");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(createAccount).toHaveBeenCalledWith("the-token", "ann@example.com", "long enough password");
  });

  it("showsTheServerError", async () => {
    const createAccount = vi.fn().mockRejectedValue(new ApiError(401, "Unauthorized"));
    renderPage(<CreateAccount />, { createAccount }, { signedInAs: null });

    await fill("wrong", "ann@example.com", "long enough password");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText("That setup token is not valid.")).toBeInTheDocument();
  });

  it("sendsPeopleToSignInWhenSetupIsAlreadyFinished", async () => {
    const createAccount = vi.fn().mockRejectedValue(new ApiError(404, "Not Found"));
    renderPage(<CreateAccount />, { createAccount }, { signedInAs: null });

    await fill("tok", "ann@example.com", "long enough password");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/setup is already finished/i)).toBeInTheDocument();
  });

  it("tellsWhereToFindTheSetupToken", () => {
    renderPage(<CreateAccount />, {}, { signedInAs: null });
    expect(screen.getByText(/SETUP TOKEN/)).toBeInTheDocument();
  });
});
