export function formatDuration(durationMs?: number | null): string {
  if (durationMs == null || Number.isNaN(durationMs) || durationMs < 0) {
    return '';
  }
  if (durationMs < 1000) {
    return `${Math.round(durationMs)}ms`;
  }
  if (durationMs < 60_000) {
    return `${(durationMs / 1000).toFixed(1)}s`;
  }
  const minutes = Math.floor(durationMs / 60_000);
  const seconds = Math.floor((durationMs % 60_000) / 1000);
  if (durationMs < 3_600_000) {
    return `${minutes}m ${seconds}s`;
  }
  const hours = Math.floor(durationMs / 3_600_000);
  const remMinutes = Math.floor((durationMs % 3_600_000) / 60_000);
  return `${hours}h ${remMinutes}m ${seconds}s`;
}

export function formatBytes(bytes?: number | null): string {
  if (bytes == null || Number.isNaN(bytes) || bytes < 0) {
    return '';
  }
  if (bytes < 1024) {
    return `${Math.round(bytes)} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}
