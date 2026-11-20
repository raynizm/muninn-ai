# Whisper

Compose runs [Speaches](https://github.com/speaches-ai/speaches) (the successor of faster-whisper-server) as the `whisper` service. It serves an OpenAI-compatible API on port 8000 inside the compose network, so Muninn reaches it at `http://whisper:8000`.

## Download a model

Speaches only lists and serves models it has downloaded. After `docker compose up`, fetch one:

```bash
docker compose exec whisper \
  curl -sf -X POST http://localhost:8000/v1/models/Systran/faster-whisper-small
```

`Systran/faster-whisper-small` is a reasonable CPU default. `...-medium` and `...-large-v3` are more accurate and slower. Downloads are kept in the `whisper-models` volume.

## Connect it to Muninn

In the setup wizard, add a provider with base URL `http://whisper:8000` and no API key, press **Discover models**, and pick the downloaded model as the transcription model. The `whisper` host is on your server, so Muninn marks it as local: audio does not leave the machine.

## GPU

The CPU image is the default. For an NVIDIA GPU, change the image in `docker-compose.yml` to `ghcr.io/speaches-ai/speaches:latest-cuda`, set `WHISPER_COMPUTE_TYPE=float16`, and give the service access to the GPU (`deploy.resources.reservations.devices`). See the Speaches documentation.

## Limits

Cloud transcription APIs often cap uploads (OpenAI: 25 MB). Muninn accepts up to 200 MB per recording, so very long recordings can fail against such APIs. A local Whisper has no such cap.
