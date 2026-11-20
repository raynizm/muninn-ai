# Muninn

**A memory for everything you say. Yours, on your own server.**

Muninn is an open source, self-hosted personal AI memory. Record a meeting, a thought or a voice note on your phone. Muninn transcribes it, summarizes it, pulls out the decisions and action items, and keeps it all in a place only you and your team control. Later, you can ask it what was said.

> **Status:** milestone 1 is under construction. The backend (login, setup, providers, uploads) is being built first; the web app and phone app follow. See [What works today](#what-works-today).

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
- **Multi-user, SSO only.** Everyone signs in with the work account they already have: Microsoft Entra or Google Workspace (OIDC). There are no passwords to leak. The admin sets which email domains are allowed.
- **Private by default.** Every user sees only their own recordings. Admins manage users and settings, but cannot read other people's recordings or transcripts.
- **Bring your own AI.** Use a local model (Ollama, vLLM, llama.cpp, LM Studio) or any OpenAI-compatible service. The admin sets a default for the whole workplace, and users can set their own if the admin allows it.
- **Secrets stay on the server.** Provider API keys are encrypted at rest and never sent to the phone or browser.
- **Works offline.** The phone records and queues uploads without a connection, and resumes when it is back. (Phones limit background work, so uploads are not promised while the app is closed.)
- **Safe retries.** Uploads are idempotent, so a flaky network can't create duplicate recordings or transcripts.
- **Setup wizard.** The first run walks the admin through SSO, allowed domains and the AI provider, with a connection test.
- **You keep your data.** Audio is kept until you delete it. Deleting a recording removes the audio and everything derived from it.
- **AGPL-3.0.** Free software, and it stays free.

## What works today

| Area | Status |
|---|---|
| Database schema and migrations | Done |
| Encrypted secret storage | Done |
| First-run setup token and recovery | Done |
| SSO login (Microsoft, Google) with own session tokens | Done |
| AI provider registry (OpenAI-compatible), model discovery | Done |
| Setup wizard and admin settings API | Done |
| Recording upload and access API | In progress |
| Transcription and summaries | Planned (milestone 1) |
| Web app | Planned (milestone 1) |
| Phone app (iOS and Android) | Planned (milestone 1) |
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

You need a Linux server with Docker and Docker Compose, plus an OIDC app registration in Microsoft Entra or Google Cloud, and an AI endpoint (for example Ollama on the same machine). Put the server behind a reverse proxy with HTTPS. Phones and SSO providers expect it.

### 1. Create the master key

Provider keys and the SSO client secret are encrypted with a master key. It must be exactly 32 raw bytes, with no trailing newline:

```bash
head -c 32 /dev/urandom > muninn_master_key
chmod 600 muninn_master_key
```

Back it up. Without it, saved provider keys cannot be decrypted. The backend reads it from the file named by `MUNINN_MASTER_KEY_FILE`, and the compose file mounts it as a Docker secret.

### 2. Start the stack

```bash
cd infrastructure
docker compose up -d --build
```

Recordings are stored under the directory set by `MUNINN_DATA_DIR`. Keep it on a persistent volume and back it up.

### 3. Get the setup token

On first start, the backend prints a one-time setup token to its log:

```bash
docker compose logs backend | grep -i setup
```

### 4. Register Muninn with your identity provider

Create an OIDC web application in Microsoft Entra or Google Cloud. Set the redirect URI to your Muninn address and note the issuer URL, client ID and client secret.

### 5. Run the setup wizard

Open your Muninn address in a browser. The wizard asks for:

1. The setup token.
2. The OIDC issuer, client ID and client secret, and the **allowed email domains**, for example `yourcompany.com`. At least one domain is required. Without it, anyone with a Google account could sign in.
3. You sign in through SSO. You become the first admin.
4. The AI provider URL and key (for example `http://ollama:11434` for a local Ollama), then the chat and transcription models.
5. Finish. Tell your coworkers the address.

### Recovery

If SSO is misconfigured, restart the backend with `--muninn.reissue-setup-token=true`. It prints a new token that can be used once to replace the SSO settings. Recovery works with the **same issuer**. Switching to a different issuer locks existing users out, because accounts are tied to the identity provider's user ID.

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
