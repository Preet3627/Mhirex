/**
 * Shared "newest Mhirex release" lookup.
 *
 * Why this file exists
 * --------------------
 * /api/apk decides which APK a visitor actually downloads, and /api/release
 * decides which version string the page shows. If those two ever resolve the
 * release by different rules, the site can advertise one version and hand over
 * another, and nothing would look broken. So the rule lives here once and both
 * import it.
 *
 * The rule: the newest non-draft release that actually ships an APK.
 * Pre-releases are included on purpose, because every Mhirex release is a
 * pre-release and GitHub's /releases/latest ignores those entirely. Verified
 * against the live repo, not assumed.
 *
 * "Newest" is decided by timestamp, NOT by position in the response. GitHub's
 * GET /releases is documented only as returning releases "in reverse order of
 * creation", and it does not honour that: on this repo it returned
 * beta9, beta8, beta10 for v1.0-beta10 (created 18:29), beta9 (16:53) and
 * beta8 (15:22). Taking [0] therefore served beta9 while beta10 was live, and
 * the version label agreed with it, so nothing looked broken. The timestamps
 * are sorted explicitly below instead of being assumed.
 *
 * This runs on Vercel's server, never in the visitor's browser, so an
 * unauthenticated GitHub rate limit (60/hr per IP) is paid once at the edge
 * rather than once per phone.
 */

export const REPO = "Preet3627/Mhirex";
const API = `https://api.github.com/repos/${REPO}/releases`;

/** All APK assets on a release, or an empty array. */
function apkAssets(release) {
  return (release.assets || []).filter((a) => a && a.name && a.name.endsWith(".apk"));
}

/**
 * Sort key: when the release went public, falling back to when it was created
 * (published_at is null for a draft, and a draft is filtered out anyway).
 * Undated releases sort oldest so they can never win by accident.
 */
function releaseTime(release) {
  const t = Date.parse(release.published_at || release.created_at || "");
  return Number.isNaN(t) ? 0 : t;
}

/**
 * The newest non-draft release that ships an APK, or throws.
 * @returns {Promise<object>} the GitHub release object
 */
export async function fetchLatestRelease() {
  const resp = await fetch(API, {
    headers: {
      Accept: "application/vnd.github+json",
      "User-Agent": "mhirex-landing-page",
    },
  });
  if (!resp.ok) throw new Error(`GitHub API ${resp.status}`);

  const releases = await resp.json();
  if (!Array.isArray(releases) || releases.length === 0) {
    throw new Error("no releases returned");
  }

  const release = releases
    .filter((r) => r && !r.draft && apkAssets(r).length > 0)
    .sort((a, b) => releaseTime(b) - releaseTime(a))[0];

  if (!release) throw new Error("no release with an APK asset");
  return release;
}

/**
 * The APK a modern phone should be offered: arm64 where one exists, otherwise
 * the first. Deliberately the same choice the download resolver makes, so the
 * size shown on the page is the size of the file that gets served.
 */
export function pickArm64Asset(release) {
  const apks = apkAssets(release);
  if (apks.length === 0) return null;
  return apks.find((a) => a.name.includes("arm64")) || apks[0];
}

/** Human-facing size label, matching the "~51.7 MB" format already in use. */
export function sizeLabelMb(asset) {
  if (!asset || !asset.size) return null;
  return Number((asset.size / (1024 * 1024)).toFixed(1));
}
