// @vitest-environment node
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { generatePkce } from "./pkce";

describe("generatePkce", () => {
  it("challengeIsS256OfVerifier", async () => {
    const { verifier, challenge } = await generatePkce();
    const expected = createHash("sha256").update(verifier).digest("base64url");
    expect(challenge).toBe(expected);
  });

  it("verifierIsUrlSafeAndLongEnough", async () => {
    const { verifier } = await generatePkce();
    expect(verifier).toMatch(/^[A-Za-z0-9_-]{43,128}$/);
  });

  it("generatesDifferentVerifiersEachTime", async () => {
    const a = await generatePkce();
    const b = await generatePkce();
    expect(a.verifier).not.toBe(b.verifier);
  });
});
