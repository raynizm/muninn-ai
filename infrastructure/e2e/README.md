# End-to-end checks

## Automated

`backend/src/test/java/se/tcmt/muninn/integration/E2EIT.java` drives the whole backend over HTTP against a real PostgreSQL (Testcontainers), a fake OpenID provider and a fake OpenAI-compatible server:

setup wizard → first admin → SSO sign-in as a normal user → upload of an m4a → background processing → transcript and summary → another user and the admin get 404.

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

### Sign-in

- [ ] **Microsoft Entra**: register a web app with redirect URI `https://<server>/login/callback`, run the wizard with the Entra preset and a tenant ID. Sign in with an account from an allowed domain. An account from another domain is refused.
- [ ] **Google Workspace**: same with the Google preset. An account whose email is not verified, or from another domain, is refused.
- [ ] Setup endpoints answer 404 after setup is finished, and the setup token no longer works.

### AI

- [ ] **Real Whisper** (bundled): upload a 1–2 minute recording; the transcript matches what was said and has timestamps.
- [ ] **Real Ollama** (or any OpenAI-compatible chat model): the summary and action items are sensible. A non-JSON answer from a weak model ends in a clear `Failed` message, not a crash.
- [ ] A cloud provider shows the "data leaves your server" warning; a local one does not.
- [ ] Stop the AI server, upload a recording, start the server again within about 13 minutes: the recording completes by itself (retries). Leave it off longer: the recording shows `Failed`.

### Phone (development build, not Expo Go)

Build once with `cd mobile && npx expo run:android` or `npx expo run:ios`.

- [ ] First launch asks for the server address; SSO sign-in in the system browser returns to the app.
- [ ] Record: the microphone prompt appears and the recording indicator is visible while recording.
- [ ] **Offline**: switch on airplane mode, record, force-close and reopen the app. The recording is still listed as `Queued`.
- [ ] Switch airplane mode off. The recording uploads without opening anything, and appears on the web list within about 10 seconds.
- [ ] Record, then kill the app during the upload. After reopening, it finishes and the web list shows it once (no duplicate).
- [ ] The detail screen shows summary, action items and transcript when processing is done, and plays the audio.
- [ ] Delete a recording on the phone: it disappears on the web too. With the server unreachable, delete refuses and keeps everything.

### Privacy

- [ ] A second user cannot open the first user's recording by id (404), nor can the admin.
- [ ] The provider API key never appears in any API response or in the server log.
