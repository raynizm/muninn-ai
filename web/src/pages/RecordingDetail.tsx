import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ApiError } from "@muninn/api";
import { useApi } from "../api";
import { isFinished, StatusBadge } from "../components/StatusBadge";
import { formatClock, formatDate, formatDuration } from "../format";

export function RecordingDetail() {
  const { id = "" } = useParams();
  const api = useApi();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ["recording", id],
    queryFn: () => api.getRecording(id),
    refetchInterval: (q) => (q.state.data && isFinished(q.state.data.processingStatus) ? false : 5000),
  });

  useEffect(() => () => void (audioUrl && URL.revokeObjectURL(audioUrl)), [audioUrl]);

  if (query.isPending) return <p>Loading…</p>;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <>
        <p role="alert" className="error">{notFound ? "Recording not found." : "Could not load the recording."}</p>
        <Link to="/">Back to recordings</Link>
      </>
    );
  }

  const r = query.data;
  const loadAudio = () =>
    api
      .getRecordingAudio(id)
      .then((blob) => setAudioUrl(URL.createObjectURL(blob)))
      .catch(() => setError("Could not load the audio."));

  return (
    <>
      <p>
        <Link to="/">← Recordings</Link>
      </p>
      <h1>{r.title ?? "Untitled recording"}</h1>
      <p className="muted">
        {formatDate(r.createdAt)}
        {r.durationMs != null && ` · ${formatDuration(r.durationMs)}`} <StatusBadge status={r.processingStatus} />
      </p>
      {error && <p role="alert" className="error">{error}</p>}
      {r.processingStatus === "FAILED" && r.lastError && <p className="error">{r.lastError}</p>}
      {!isFinished(r.processingStatus) && <p className="muted">This recording is still being processed.</p>}

      {audioUrl ? <audio controls src={audioUrl} /> : <button onClick={() => void loadAudio()}>Play audio</button>}

      {r.summary && (
        <section>
          <h2>Summary</h2>
          <p>{r.summary.text}</p>
          {r.summary.actionItems.length > 0 && (
            <>
              <h3>Action items</h3>
              <ul>
                {r.summary.actionItems.map((item, i) => (
                  <li key={i}>{item}</li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}

      {r.transcript && (
        <section>
          <h2>Transcript</h2>
          {r.transcript.segments.length > 0 ? (
            <ol className="segments">
              {r.transcript.segments.map((s, i) => (
                <li key={i}>
                  <time className="muted">{formatClock(s.start)}</time>
                  <span>{s.text}</span>
                </li>
              ))}
            </ol>
          ) : (
            <p>{r.transcript.text}</p>
          )}
        </section>
      )}

      <section>
        {confirming ? (
          <span className="row">
            <button
              className="danger"
              onClick={() =>
                api
                  .deleteRecording(id)
                  .then(() => navigate("/"))
                  .catch(() => setError("Could not delete the recording."))
              }
            >
              Confirm delete
            </button>
            <button onClick={() => setConfirming(false)}>Cancel</button>
          </span>
        ) : (
          <button onClick={() => setConfirming(true)}>Delete recording</button>
        )}
      </section>
    </>
  );
}
