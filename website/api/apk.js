/**
 * /api/apk - automatic Mhirex APK download resolver.
 *
 * Why this exists instead of a plain link in index.html:
 *
 * 1. GitHub's /releases/latest/download/<asset> only considers NON-prerelease
 *    releases. Every Mhirex release is correctly flagged as a pre-release, so
 *    that URL returns 404. Verified against the live repo, not assumed.
 * 2. The previous fallback was a hardcoded tag in index.html, so every new
 *    release needed a manual edit, and any failure of the client-side fetch
 *    silently served a stale APK.
 * 3. Resolving in the browser (the old approach) depends on api.github.com
 *    being reachable from the visitor's phone. That fails on a rate limit
 *    (unauthenticated GitHub API is 60 req/hr per IP, and mobile carriers NAT
 *    many users behind one address), offline, or behind a privacy blocker.
 *
 * So: resolve server-side, honour pre-releases, pick the ABI that was asked for,
 * and cache at the edge. Publishing a new release needs no website change.
 *
 * The "which release is newest" rule lives in ../lib/latest-release.js because
 * /api/release needs the identical answer for the version label on the page. Two
 * copies of that rule would eventually disagree, and the site would then
 * advertise one version while serving another.
 */

import { REPO, fetchLatestRelease } from "../lib/latest-release.js";

// Only these may be used as an asset filter, so the query string can never be
// used to reach some other asset in the release.
const ABIS = {
  "arm64-v8a": "arm64",
  arm64: "arm64",
  aarch64: "arm64",
  "armeabi-v7a": "armeabi",
  arm32: "armeabi",
  armv7: "armeabi",
  armeabi: "armeabi",
  "x86_64": "x86_64",
  x64: "x86_64",
};

const DEFAULT_MATCH = "arm64";

// The releases LIST, not /releases/latest. Checked against the live repo rather
// than assumed: /releases/latest resolves only non-prerelease releases, and
// every Mhirex release is a pre-release, so it 302s to /releases anyway. Linking
// straight at the list saves the hop and makes the intent obvious. Note the
// sibling URL /releases/latest/download/<asset> really does 404 here, which is
// why the resolver above exists at all.
const RELEASES_PAGE = `https://github.com/${REPO}/releases`;

function pickAsset(release, match) {
  const apks = (release.assets || []).filter(
    (a) => a && a.name && a.name.endsWith(".apk")
  );
  if (apks.length === 0) return null;
  return (
    apks.find((a) => a.name.includes(match)) ||
    // Fall back to the arm64 build rather than an arbitrary ABI, so the common
    // case still gets a modern-phone APK on an unrecognised query.
    apks.find((a) => a.name.includes("arm64")) ||
    apks[0]
  );
}

export default async function handler(req, res) {
  const raw = String(req.query?.abi || "").toLowerCase();
  const match = ABIS[raw] || DEFAULT_MATCH;

  // Edge caching. A new release shows up on the site within ~5 minutes with no
  // deploy and no edit, and GitHub's rate limit is never in play per visitor.
  res.setHeader("Cache-Control", "s-maxage=300, stale-while-revalidate=86400");
  res.setHeader("X-Content-Type-Options", "nosniff");

  try {
    // Newest non-draft release that actually ships an APK. Pre-releases are
    // included on purpose - every Mhirex release is a pre-release.
    const release = await fetchLatestRelease();

    const asset = pickAsset(release, match);
    if (!asset?.browser_download_url) throw new Error("no matching asset");

    res.setHeader("X-Mhirex-Release", release.tag_name || "");
    res.setHeader("X-Mhirex-Asset", asset.name);
    res.setHeader("X-Mhirex-Prerelease", String(Boolean(release.prerelease)));
    // 302, not 307: the target changes with every release, so it must not be
    // cached by the browser beyond the edge TTL.
    res.setHeader("Cache-Control", "s-maxage=300, stale-while-revalidate=86400, max-age=0");
    res.writeHead(302, { Location: asset.browser_download_url });
    res.end();
  } catch (err) {
    // Never dead-end the user on an error page: send them to the releases list.
    res.setHeader("X-Mhirex-Error", String(err.message || err).slice(0, 120));
    res.writeHead(302, { Location: RELEASES_PAGE });
    res.end();
  }
}
