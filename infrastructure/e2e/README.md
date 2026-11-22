# End-to-end checks

## Automated

`backend/src/test/java/se/tcmt/muninn/integration/E2EIT.java` drives the whole backend over HTTP against a real PostgreSQL (Testcontainers), a fake OpenID provider and a fake OpenAI-compatible server:

setup wizard → first admin → SSO sign-in as a normal user → upload of an m4a → background processing → transcript and summary → another user and the admin get 404. (This test still covers the backend SSO path; the web app now uses the single-owner password flow, covered by `OwnerAccountControllerIT`.)

```bash
cd backend
./mvnw verify -Dit.test=E2EIT -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false
```

The compose stack has a smoke test (backend answers, setup token is logged):

```bash
cd infrastructure
docker compose up -d --build
sh smoke.sh
```

## Manual checklist (real services and a real phone)

These need things a test cannot fake. Run them once per release.

### Server

- [ ] `docker compose up -d --build` on a clean machine; `sh smoke.sh` prints `OK`.
- [ ] Download a Whisper model (see `whisper/README.md`); `GET /v1/models` on the whisper service lists it.
- [ ] Reverse proxy with HTTPS in front of port 8080; the web app loads over `https://`.

### Account

- [ ] Open the web app on a fresh install: "Welcome to Muninn" asks for the setup token, email and password. A wrong token is refused; a password under 10 characters is refused.
- [ ] After creating the account you are signed in and `/setup` redirects to the recordings list. A second visit never shows the create-account page.
- [ ] Sign out and sign in again with the email (any letter case) and password. A wrong password and an unknown email show the same message. Five wrong attempts in a row are refused for a while, even with the right password.
- [ ] In Settings → Account, change the email (wrong current password is refused plainly) and sign out and in with the new one. Change the password: another signed-in browser is signed out within about 15 minutes and can no longer refresh.
- [ ] **Stats** page: with a known set of recordings, totals, hours, average and the monthly bars match; a recording made just before midnight on the last day of a month counts in the right month for your time zone; recordings without a length are mentioned in the note.
- [ ] **Forgot password**: restart the backend with `--muninn.reissue-setup-token=true`, read the recovery token from the log, reset the password at `/reset-password`. The old password and the old session stop working; the token cannot be used twice.

### AI

- [ ] **Real Whisper** (bundled): upload a 1–2 minute recording; the transcript matches what was said and has timestamps.
- [ ] **Real Ollama** (or any OpenAI-compatible chat model): the summary and action items are sensible. A non-JSON answer from a weak model ends in a clear `Failed` message, not a crash.
- [ ] A cloud provider shows the "data leaves your server" warning; a local one does not.
- [ ] Stop the AI server, upload a recording, start the server again within about 13 minutes: the recording completes by itself (retries). Leave it off longer: the recording shows `Failed`.

### Phone (development build, not Expo Go)

Build once with `cd mobile && npx expo run:android` or `npx expo run:ios`.

- [ ] First launch asks for the server address. (Sign-in with email and password on the phone is the next change; the old SSO flow no longer applies.)
- [ ] Record: the microphone prompt appears and the recording indicator is visible while recording.
- [ ] **Offline**: switch on airplane mode, record, force-close and reopen the app. The recording is still listed as `Queued`.
- [ ] Switch airplane mode off. The recording uploads without opening anything, and appears on the web list within about 10 seconds.
- [ ] Record, then kill the app during the upload. After reopening, it finishes and the web list shows it once (no duplicate).
- [ ] The detail screen shows summary, action items and transcript when processing is done, and plays the audio.
- [ ] Delete a recording on the phone: it disappears on the web too. With the server unreachable, delete refuses and keeps everything.

### Privacy

- [ ] There is no way to create a second account through the web app or the API.
- [ ] The provider API key never appears in any API response or in the server log.
