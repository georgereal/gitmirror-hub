/**
 * Same repository key as webhook-worker-kafka so one repo stays on one partition.
 */
export function repoKey(url) {
  let s = String(url ?? "").trim().toLowerCase().replace(/\/+$/, "").replace(/\.git$/, "");
  s = s.replace(/^(https?|ssh|git):\/\//, "");
  s = s.replace(/^git@([^:]+):/, "$1/");
  s = s.replace(/^[^@/]+@/, "");
  return s;
}
