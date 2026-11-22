import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@muninn/api";
import { renderPage, user } from "../test-utils";
import { AccountSettings } from "./AccountSettings";

const tokenResponse = { accessToken: "A", refreshToken: "R", expiresIn: 900, user: user("ADMIN") };

describe("AccountSettings", () => {
  it("changesTheEmailWithTheTypedValues", async () => {
    const changeEmail = vi.fn(async () => ({ ...user("ADMIN"), email: "new@example.com" }));
    renderPage(<AccountSettings />, { changeEmail }, { signedInAs: "ADMIN" });
    const form = await screen.findByRole("form", { name: /change email/i });

    await userEvent.type(within(form).getByLabelText(/new email/i), "new@example.com");
    await userEvent.type(within(form).getByLabelText(/current password/i), "my current password");
    await userEvent.click(within(form).getByRole("button", { name: /save email/i }));

    expect(changeEmail).toHaveBeenCalledWith("my current password", "new@example.com");
    expect(await screen.findByText("Email changed.")).toBeInTheDocument();
  });

  it("showsAWrongCurrentPasswordPlainly", async () => {
    const changeEmail = vi.fn().mockRejectedValue(new ApiError(403, "Forbidden"));
    renderPage(<AccountSettings />, { changeEmail }, { signedInAs: "ADMIN" });
    const form = await screen.findByRole("form", { name: /change email/i });

    await userEvent.type(within(form).getByLabelText(/new email/i), "new@example.com");
    await userEvent.type(within(form).getByLabelText(/current password/i), "wrong");
    await userEvent.click(within(form).getByRole("button", { name: /save email/i }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("Your current password is wrong.");
  });

  it("explainsAnEmailThatIsAlreadyInUse", async () => {
    const changeEmail = vi.fn().mockRejectedValue(new ApiError(409, "Conflict"));
    renderPage(<AccountSettings />, { changeEmail }, { signedInAs: "ADMIN" });
    const form = await screen.findByRole("form", { name: /change email/i });

    await userEvent.type(within(form).getByLabelText(/new email/i), "taken@example.com");
    await userEvent.type(within(form).getByLabelText(/current password/i), "my current password");
    await userEvent.click(within(form).getByRole("button", { name: /save email/i }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("That email is already in use.");
  });

  it("blocksPasswordChangeWhenTooShortOrMismatched", async () => {
    renderPage(<AccountSettings />, {}, { signedInAs: "ADMIN" });
    const form = await screen.findByRole("form", { name: /change password/i });
    const button = within(form).getByRole("button", { name: /change password/i });

    await userEvent.type(within(form).getByLabelText(/current password/i), "my current password");
    await userEvent.type(within(form).getByLabelText(/^new password/i), "short");
    await userEvent.type(within(form).getByLabelText(/confirm new password/i), "short");
    expect(button).toBeDisabled();
    expect(within(form).getByText(/at least 10 characters/i)).toBeInTheDocument();

    await userEvent.clear(within(form).getByLabelText(/^new password/i));
    await userEvent.type(within(form).getByLabelText(/^new password/i), "a brand new password");
    expect(button).toBeDisabled();
    expect(within(form).getByText(/do not match/i)).toBeInTheDocument();
  });

  it("changesThePasswordAndExplainsOtherDevicesAreSignedOut", async () => {
    const changePassword = vi.fn(async () => tokenResponse);
    renderPage(<AccountSettings />, { changePassword }, { signedInAs: "ADMIN" });
    const form = await screen.findByRole("form", { name: /change password/i });

    await userEvent.type(within(form).getByLabelText(/current password/i), "my current password");
    await userEvent.type(within(form).getByLabelText(/^new password/i), "a brand new password");
    await userEvent.type(within(form).getByLabelText(/confirm new password/i), "a brand new password");
    await userEvent.click(within(form).getByRole("button", { name: /change password/i }));

    expect(changePassword).toHaveBeenCalledWith("my current password", "a brand new password");
    expect(await screen.findByText("Password changed. Other devices are signed out.")).toBeInTheDocument();
  });
});
