import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { MonthStat } from "@muninn/api";
import { useApi } from "../api";
import { formatClock } from "../format";

const hours = (ms: number) => (ms / 3_600_000).toFixed(1);

/** "2026-11" as "Nov 2026" (and "Nov" for axis labels), independent of the viewer's time zone. */
function monthLabel(month: string, withYear: boolean): string {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleString(undefined, {
    month: "short",
    ...(withYear ? { year: "numeric" } : {}),
    timeZone: "UTC",
  });
}

export function Stats() {
  const api = useApi();
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const query = useQuery({ queryKey: ["stats", timeZone], queryFn: () => api.getStats(timeZone) });

  if (query.isPending) return <p>Loading…</p>;
  if (query.isError) {
    return (
      <p role="alert" className="error">
        Could not load the statistics.
      </p>
    );
  }

  const s = query.data;
  const missing = s.totalRecordings - s.recordingsWithDuration;
  return (
    <>
      <h1>Stats</h1>
      <div className="tiles">
        <Tile label="Recordings" value={String(s.totalRecordings)} />
        <Tile label="Hours recorded" value={hours(s.totalDurationMs)} />
        <Tile label="Average length" value={s.averageDurationMs == null ? "–" : formatClock(s.averageDurationMs / 1000)} />
        <Tile label="This month" value={String(s.thisMonthRecordings)} />
      </div>
      {missing > 0 && (
        <p className="muted small">
          {missing} {missing === 1 ? "recording has" : "recordings have"} no length and {missing === 1 ? "is" : "are"} not
          counted in hours or the average.
        </p>
      )}
      {s.totalRecordings === 0 ? (
        <p className="muted">No recordings yet. Record with the Muninn phone app and the numbers show up here.</p>
      ) : (
        <>
          <section>
            <h2>Recordings per month</h2>
            <MonthChart months={s.byMonth} />
          </section>
          <section>
            <h2>By month</h2>
            <table className="models">
              <thead>
                <tr>
                  <th>Month</th>
                  <th>Recordings</th>
                  <th>Hours</th>
                </tr>
              </thead>
              <tbody>
                {s.byMonth.map((m) => (
                  <tr key={m.month}>
                    <td>{m.month}</td>
                    <td>{m.count}</td>
                    <td>{hours(m.durationMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </>
      )}
    </>
  );
}

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="tile">
      <span className="tile-label muted small">{label}</span>
      <span className="tile-value">{value}</span>
    </div>
  );
}

const W = 720;
const H = 240;
const PAD = { top: 12, right: 8, bottom: 28, left: 32 };
const BAR_RATIO = 0.55;

/** Step for the y axis: 1, 2, 5, 10, 20, 50 ... so there are at most about four gridlines. */
function niceStep(max: number): number {
  const raw = Math.max(1, max) / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * pow;
}

/** Path of a bar with a 4px rounded top and a square base, so it sits flat on the baseline. */
function barPath(x: number, y: number, w: number, h: number): string {
  const r = Math.min(4, w / 2, h);
  if (h <= 0) return `M${x},${y}h${w}`;
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

function MonthChart({ months }: { months: MonthStat[] }) {
  const [active, setActive] = useState<number | null>(null);
  const max = Math.max(...months.map((m) => m.count), 1);
  const step = niceStep(max);
  const top = Math.ceil(max / step) * step;
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const slot = plotW / months.length;
  const barW = slot * BAR_RATIO;
  const yOf = (v: number) => PAD.top + plotH - (v / top) * plotH;
  const ticks: number[] = [];
  for (let v = 0; v <= top; v += step) ticks.push(v);
  const peak = months.reduce((a, b) => (b.count > a.count ? b : a));
  const hovered = active == null ? null : months[active];

  return (
    <div className="chart" onMouseLeave={() => setActive(null)}>
      <div className="chart-tip" aria-live="polite" style={{ visibility: hovered ? "visible" : "hidden", left: active == null ? 0 : `${((PAD.left + slot * (active + 0.5)) / W) * 100}%` }}>
        {hovered && (
          <>
            <strong>{monthLabel(hovered.month, true)}</strong>
            <span>
              {hovered.count} {hovered.count === 1 ? "recording" : "recordings"} · {hours(hovered.durationMs)} h
            </span>
          </>
        )}
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Recordings per month. Most in ${monthLabel(peak.month, true)}: ${peak.count}.`}>
        {ticks.map((v) => (
          <g key={v}>
            <line className="grid" x1={PAD.left} x2={W - PAD.right} y1={yOf(v)} y2={yOf(v)} />
            <text className="axis" x={PAD.left - 6} y={yOf(v) + 4} textAnchor="end">
              {v}
            </text>
          </g>
        ))}
        {months.map((m, i) => {
          const x = PAD.left + slot * i + (slot - barW) / 2;
          const y = yOf(m.count);
          return (
            <g key={m.month}>
              <path data-testid="bar" className={active === i ? "bar active" : "bar"} d={barPath(x, y, barW, PAD.top + plotH - y)} />
              <text className="axis" x={x + barW / 2} y={H - 8} textAnchor="middle">
                {monthLabel(m.month, false)}
              </text>
              {/* Full-height hit area, wider than the bar. */}
              <rect
                className="hit"
                x={PAD.left + slot * i}
                y={PAD.top}
                width={slot}
                height={plotH}
                tabIndex={0}
                aria-label={`${monthLabel(m.month, true)}: ${m.count} recordings, ${hours(m.durationMs)} hours`}
                onMouseEnter={() => setActive(i)}
                onFocus={() => setActive(i)}
                onBlur={() => setActive(null)}
              />
            </g>
          );
        })}
      </svg>
    </div>
  );
}
