/**
 * /api/release - the current Mhirex version, for the labels on the page.
 *
 * Why this exists instead of the browser fetching api.github.com
 * ----------------------------------------------------------------
 * The version and size shown on the page used to be filled in by script.js
 * calling api.github.com directly from the visitor's phone. That has the exact
 * problems /api/apk was written to avoid, and they were accepted there for a
 * reason that applies here too:
 *
 *   - unauthenticated GitHub is 60 req/hr per IP, and mobile carriers NAT many
 *     phones behind one address, so a busy afternoon rate-limits everyone;
 *   - it fails offline and behind a privacy blocker;
 *   - on any of those failures it silently kept a hardcoded fallback, so the
 *     page quietly advertised a stale version with no visible sign of it.
 *
 * So the version is resolved server-side from the same shared rule the download
 * uses (see lib/latest-release.js), edge-cached on the same TTL, and a
 * GitHub hiccup degrades to "keep whatever the page already shows" rather than
 * to a wrong number.
 *
 * The hardcoded strings in index.html remain as a last-resort fallback for when
 * this endpoint itself is unreachable. They are not maintained by hand as a
 * matter of routine: this endpoint is what normally fills them in.
 */

import { REPO, fetchLatestRelease, pickArm64Asset, sizeLabelMb } from "../lib/latest-release.js";

export default async function handler(req, res) {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("X-Content-Type-Options", "nosniff");
  // Same TTL and intent as /api/apk, so the advertised version and the served
  // APK refresh on the same schedule and can never be a release apart.
  res.setHeader("Cache-Control", "s-maxage=300, stale-while-revalidate=86400, max-age=0");

  try {
    const release = await fetchLatestRelease();
    const asset = pickArm64Asset(release);

    res.statusCode = 200;
    res.end(
      JSON.stringify({
        ok: true,
        tag: release.tag_name || "",
        prerelease: Boolean(release.prerelease),
        publishedAt: release.published_at || null,
        sizeMb: sizeLabelMb(asset),
        asset: asset?.name || null,
        url: release.tag_name
          ? `https://github.com/${REPO}/releases/tag/${release.tag_name}`
          : `https://github.com/${REPO}/releases`,
      })
    );
  } catch (err) {
    // 200 with ok:false on purpose. A non-2xx would make the page treat a
    // transient GitHub failure as "there is no version", which is worse than
    // showing the last value it already had.
    res.statusCode = 200;
    res.setHeader("X-Mhirex-Error", String(err.message || err).slice(0, 120));
    res.end(JSON.stringify({ ok: false, error: String(err.message || err).slice(0, 120) }));
  }
}
