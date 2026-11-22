# Muninn

**A memory for everything you say. Yours, on your own server.**

Muninn is an open source, self-hosted personal AI memory. Record a meeting, a thought or a voice note on your phone. Muninn transcribes it, summarizes it, pulls out the decisions and action items, and keeps it all in a place only you control. Later, you can ask it what was said.

> **Status:** milestone 1 is feature complete, and Muninn is now a single-user app: one account, email and password, no SSO and no other users. The web app uses it. The phone app still signs in through the old SSO flow and needs an email and password form next. Run the [manual checklist](infrastructure/e2e/README.md) on a real device before relying on it. See [What works today](#what-works-today).

## Why "Muninn"?

In Norse myth, the god Odin keeps two ravens, **Huginn** and **Muninn**. Every morning he sends them out across the world, and every evening they fly back and whisper into his ear what they saw and heard. Huginn is *thought*. **Muninn is memory**: the name comes from Old Norse *munr*, "mind" or "memory".

The *Poetic Edda* records Odin's worry about them: he fears that Huginn might not return, but he fears more for Muninn. A mind can lose a thought and recover. Lose your memory and you lose what you learned.

That is the idea behind this project. What people say at work holds real value, and it vanishes as soon as the call ends. Muninn flies out with you, listens, and comes back to tell you what was said.

## What it does

1. **Record.** Press record on your phone. Audio is saved on the device first, so nothing is lost if the server is unreachable.
2. **Upload.** When the server is reachable, recordings upload automatically. Retries are safe: a recording is never duplicated.
3. **Understand.** The server transcribes the audio with Whisper and uses an AI model to write a summary and list action items.
4. **Read and manage.** Open the web app or the phone app to read transcripts, play the audio, and delete anything you no longer want.

The long-term goal is a searchable personal memory you can question: *"What did we decide about the database migration?"*, *"What did my manager say about the project?"* Search and chat over memories are planned for a later milestone.

## Features

- **Self-hosted.** One `docker compose up`. Your audio and transcripts stay on your hardware.
- **Just for you.** You create one account (email and password) when you set Muninn up, and nobody else can sign up. There is no organization, no admin and no other users. Muninn never sends email.
- **Private by default.** Your recordings are only yours. The server runs where you decide, and nothing leaves it except what you send to the AI provider you choose.
- **Bring your own AI.** Use a local model (Ollama, vLLM, llama.cpp, LM Studio) or any OpenAI-compatible service. Pick your providers and default models in Settings.
- **Secrets stay on the server.** Provider API keys are encrypted at rest and never sent to the phone or browser.
- **Works offline.** The phone records and queues uploads without a connection, and resumes when it is back. (Phones limit background work, so uploads are not promised while the app is closed.)
- **Safe retries.** Uploads are idempotent, so a flaky network can't create duplicate recordings or transcripts.
- **Simple first run.** Open the web app, paste the one-time setup token from the server log, and choose your email and password. Forgot the password? A recovery token from the server log lets you set a new one.
- **You keep your data.** Audio is kept until you delete it. Deleting a recording removes the audio and everything derived from it.
- **AGPL-3.0.** Free software, and it stays free.

## What works today

| Area | Status |
|---|---|
| Database schema and migrations | Done |
| Encrypted secret storage | Done |
| First-run setup token and recovery | Done |
| SSO login (Microsoft, Google) in the backend | Done, not used by the web app any more |
| AI provider registry (OpenAI-compatible), model discovery | Done |
| Single owner account: first-run creation, email and password login, password reset | Done |
| Recording upload and access API | Done |
| Transcription and summaries | Done |
| Docker Compose (backend, Postgres, Whisper) | Done |
| Web app (create account, sign-in, recordings, settings) | Done |
| Phone app (iOS and Android): record, offline queue, upload, list and detail | Done, needs a real-device check |
| Phone app sign-in with email and password | Next (it still uses the old SSO browser flow) |
| Search and chat over memories, multi-model routing, Gemini and Anthropic | Later milestones |

## How it is built

- **Backend:** Java 21, Spring Boot, PostgreSQL, Flyway. Jobs run from a Postgres table, so there is no extra queue to run.
- **Web app:** React, Vite, TypeScript, served by the backend as static files.
- **Phone app:** Expo (React Native), TypeScript, SQLite for the offline queue.
- **AI:** Whisper (faster-whisper) for transcription and any OpenAI-compatible endpoint for summaries.

```
muninn-ai/
  backend/          Spring Boot API
  web/              React web app
  mobile/           Expo phone app
  packages/api/     shared TypeScript API client
  infrastructure/   docker-compose.yml, Whisper
```

## Setting it up

You need a machine with Docker and Docker Compose, and an AI endpoint (for example Ollama on the same machine). If it is reachable from the internet, put it behind a reverse proxy with HTTPS.

### 1. Create the master key

Provider keys are encrypted with a master key. It must be exactly 32 raw bytes, with no trailing newline (so do not create it with `echo`):

```bash
cd infrastructure
mkdir -p secrets
head -c 32 /dev/urandom > secrets/muninn_master_key
chmod 600 secrets/muninn_master_key
```

Back it up. Without it, saved provider keys cannot be decrypted. The compose file mounts it as the Docker secret `muninn_master_key`, and the backend reads it from the path in `MUNINN_MASTER_KEY_FILE`.

### 2. Start the stack

```bash
cp .env.example .env   # set POSTGRES_PASSWORD and, if you like, the port
docker compose up -d --build
```

This starts the backend (port 8080), PostgreSQL and a local Whisper server. Recordings are stored in the `muninn-data` volume (`MUNINN_DATA_DIR`). Back that volume up with the database.

Whisper starts with no model. Download one, as described in [infrastructure/whisper/README.md](infrastructure/whisper/README.md):

```bash
docker compose exec whisper curl -sf -X POST http://localhost:8000/v1/models/Systran/faster-whisper-small
```

To check the stack, run `sh smoke.sh` from `infrastructure/`.

### 3. Get the setup token

On first start, the backend prints a one-time setup token to its log:

```bash
docker compose logs backend | grep "SETUP TOKEN"
```

### 4. Create your account

Open your Muninn address in a browser. Because nobody has an account yet, you see "Welcome to Muninn":

1. Paste the setup token from the log.
2. Choose your email and password (at least 10 characters). The email is only your login name: Muninn never sends email.
3. That creates the only account and closes setup for good. Nobody else can sign up or be added.

### 5. Add an AI provider

Open **Settings**. Add a provider, for example `http://ollama:11434` for a local Ollama or `http://whisper:8000` for the bundled Whisper, test the connection, then choose your default summary (chat) and transcription models. The bundled Whisper only transcribes, so a chat model needs a second provider.

### Forgot your password

Restart the backend with `--muninn.reissue-setup-token=true`. It prints a one-time recovery token:

```bash
docker compose logs backend | grep "RECOVERY TOKEN"
```

Open `/reset-password` in the web app, paste the token and choose a new password. This signs you out everywhere.

## Development

Requirements: Java 21, Node 22, Docker (for the integration tests).

```bash
# backend: unit tests, integration tests (Testcontainers) and formatting check
cd backend && ./mvnw verify

# shared API client and web app
npm install
npm test -w @muninn/api
npm run dev -w @muninn/web     # http://localhost:5173, proxies /api to localhost:8080
npm test -w @muninn/web

# phone app: tests, then a development build on a device or emulator (Expo Go is not supported)
npm test -w @muninn/mobile
cd mobile && npx expo run:android   # or: npx expo run:ios
```

The built web app is bundled into the backend jar (`npm run build -w @muninn/web`, then `./mvnw package`); the Docker image does both.

## Privacy and responsible use

Voice recordings contain sensitive information, often about other people. Muninn tries to make this visible: the recording state is always clear, and you can delete anything. But the tool can't decide what is appropriate for you.

- Tell people when you record them, and get consent where the law or your workplace requires it.
- In Sweden and the EU, recording and storing conversations can fall under data protection law (GDPR). Check the rules before using Muninn in situations that involve other people.
- A technically permitted recording is not automatically appropriate to send to a cloud AI provider. Muninn shows when a provider is not local, meaning your data would leave your server. Prefer a local model for private conversations.

## Contributing

Issues and pull requests are welcome. Commit messages are lowercase and start with `feat:`, `fix:`, `refactor:` or `chore:`.

Java code is formatted with [Spotless](https://github.com/diffplug/spotless). `./mvnw verify` fails if code is not formatted, so run this before you commit:

```bash
cd backend
./mvnw spotless:apply
```

## License

[GNU AGPL-3.0](LICENSE). If you run a modified Muninn as a service for others, you must share your changes.
