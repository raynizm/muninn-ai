import { useState } from "react";
import { Link } from "react-router-dom";
import { StatusBadge } from "../components/StatusBadge";
import { formatDate, formatDuration } from "../format";
import { useRecordings } from "../hooks/useRecordings";

export function Recordings() {
  const { data, isPending, isError, remove } = useRecordings();
  const [confirming, setConfirming] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (isPending) return <p>Loading…</p>;
  if (isError) return <p role="alert" className="error">Could not load recordings.</p>;

  return (
    <>
      <h1>Recordings</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {data.length === 0 ? (
        <p className="muted">No recordings yet. Record with the Muninn phone app and they will show up here.</p>
      ) : (
        <ul className="list">
          {data.map((r) => (
            <li key={r.id}>
              <div className="grow">
                <Link to={`/recordings/${r.id}`}>{r.title ?? "Untitled recording"}</Link>
                <div className="muted small">
                  {formatDate(r.createdAt)}
                  {r.durationMs != null && ` · ${formatDuration(r.durationMs)}`}
                </div>
                {r.processingStatus === "FAILED" && r.lastError && <div className="error small">{r.lastError}</div>}
              </div>
              <StatusBadge status={r.processingStatus} />
              {confirming === r.id ? (
                <span className="row">
                  <button
                    className="danger"
                    onClick={() =>
                      remove(r.id)
                        .then(() => setConfirming(null))
                        .catch(() => setError("Could not delete the recording."))
                    }
                  >
                    Confirm delete
                  </button>
                  <button onClick={() => setConfirming(null)}>Cancel</button>
                </span>
              ) : (
                <button onClick={() => setConfirming(r.id)}>Delete</button>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
