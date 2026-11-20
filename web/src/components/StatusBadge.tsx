import type { ProcessingStatus } from "@muninn/api";

const LABELS: Record<ProcessingStatus, string> = {
  PENDING: "Waiting",
  TRANSCRIBING: "Transcribing",
  SUMMARIZING: "Summarizing",
  COMPLETED: "Ready",
  FAILED: "Failed",
};

export function StatusBadge({ status }: { status: ProcessingStatus }) {
  return <span className={`badge ${status.toLowerCase()}`}>{LABELS[status]}</span>;
}

export const isFinished = (status: ProcessingStatus) => status === "COMPLETED" || status === "FAILED";
