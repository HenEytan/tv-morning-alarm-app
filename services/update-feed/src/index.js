/**
 * TV Morning Alarm's update feed, adapted from PeakMediaPlayer's without R2. It answers the GitHub
 * releases API calls the in-app updater makes (app/…/Updater.kt), so the APK carries no token and
 * keeps updating once the repository is private.
 *
 * Every answer is proxied from GitHub with the TVALARM_GITHUB_TOKEN (or GITHUB_TOKEN) secret, the
 * shared Contents-read key the other Peak feeds use:
 *
 *   /repos/<REPO>/releases/latest                GitHub's latest release, each asset's url and
 *                                                browser_download_url pointing back at this feed
 *   /repos/<REPO>/releases/assets/<tag>/<file>   a 302 to GitHub's short-lived download URL
 *
 * The token goes to api.github.com only; nothing GitHub returns that carries it is passed on.
 */

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const SAFE = /^[A-Za-z0-9._-]+$/;
const GH_API = "https://api.github.com";
const GH_TIMEOUT_MS = 10_000;
const REDIRECTS = [301, 302, 303, 307, 308];

export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") return text("method not allowed", 405);
    const url = new URL(request.url);
    let parts;
    try {
      parts = url.pathname.split("/").filter(Boolean).map(decodeURIComponent);
    } catch {
      return notFound(); // a malformed escape is no route
    }

    const isHome = parts.length === 0;
    // /repos/<owner>/<name>/releases/latest | /repos/<owner>/<name>/releases/assets/<tag>/<file>
    const isRepo = parts[0] === "repos" && parts.length >= 5 && parts[3] === "releases" && `${parts[1]}/${parts[2]}` === env.REPO;
    const rest = isRepo ? parts.slice(4) : [];
    const isLatest = rest.length === 1 && rest[0] === "latest";
    const isAsset = rest.length === 3 && rest[0] === "assets" && SAFE.test(rest[1]) && SAFE.test(rest[2]);
    if (!isHome && !isLatest && !isAsset) return notFound();

    if (!githubToken(env)) return text("update feed not configured", 503);
    try {
      if (isHome) return await home(env, url.origin);
      if (isLatest) return await latest(env, url.origin);
      return await assetRedirect(env, rest[1], rest[2]);
    } catch {
      return badGateway(); // timeout, network failure or unreadable JSON
    }
  },
};

function githubToken(env) {
  return String(env.TVALARM_GITHUB_TOKEN || env.GITHUB_TOKEN || "");
}

async function gh(env, path, init = {}) {
  const { headers: extra, ...rest } = init;
  return fetch(GH_API + path, {
    signal: AbortSignal.timeout(GH_TIMEOUT_MS),
    ...rest,
    headers: {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "tv-morning-alarm-updates",
      Authorization: `Bearer ${githubToken(env)}`,
      ...extra,
    },
  });
}

/** Rewrite GitHub's asset urls to this feed, so the APK never needs a token. */
export function shapeGithubRelease(rel, origin, repo) {
  const tag = rel.tag_name;
  return {
    ...rel,
    html_url: `${origin}/`,
    assets: (rel.assets || []).map((a) => {
      const path = `/repos/${repo}/releases/assets/${tag}/${a.name}`;
      return { ...a, url: `${origin}${path}`, browser_download_url: `${origin}${path}` };
    }),
  };
}

async function latestRelease(env, origin) {
  const r = await gh(env, `/repos/${env.REPO}/releases/latest`);
  if (r.status === 404) return { status: 404 };
  if (!r.ok) return { status: 502 };
  return { status: 200, release: shapeGithubRelease(await r.json(), origin, env.REPO) };
}

async function latest(env, origin) {
  const { status, release } = await latestRelease(env, origin);
  if (status === 404) return notFound();
  if (status !== 200) return badGateway();
  return json(release);
}

/** GitHub answers an asset download with a 302 to its storage; pass that on, the updater follows it. */
async function assetRedirect(env, tag, file) {
  const r = await gh(env, `/repos/${env.REPO}/releases/tags/${encodeURIComponent(tag)}`);
  if (r.status === 404) return notFound();
  if (!r.ok) return badGateway();
  const asset = ((await r.json()).assets || []).find((a) => a.name === file);
  if (!asset) return notFound();
  const d = await gh(env, `/repos/${env.REPO}/releases/assets/${asset.id}`, {
    headers: { Accept: "application/octet-stream" },
    redirect: "manual",
  });
  const location = d.headers.get("location");
  if (!location || !REDIRECTS.includes(d.status)) return badGateway();
  return new Response(null, { status: 302, headers: { location, "cache-control": "no-store" } });
}

/** Dark by default with a light scheme when the device asks; the download is a 48px target. */
const PAGE_CSS = [
  ":root{color-scheme:dark light;--ink:#0E1416;--surface:#162024;--text:#E3EBEE;--muted:rgba(227,235,238,.7);--accent:#7FD3E6;--line:rgba(127,211,230,.25)}",
  "@media (prefers-color-scheme: light){:root{--ink:#F4F8F9;--surface:#FFFFFF;--text:#0E1A1E;--muted:rgba(14,26,30,.74);--accent:#00687A;--line:rgba(0,104,122,.3)}}",
  "body{margin:0;background:var(--ink);color:var(--text);font:16px/1.5 system-ui,sans-serif}",
  "main{max-width:32rem;margin:0 auto;padding:3rem 1.5rem}",
  "h1{font-size:2rem;margin:.25rem 0 .5rem}p{color:var(--muted);margin:0 0 1.5rem}",
  "ul{list-style:none;margin:0;padding:0;display:grid;gap:.75rem}",
  "a{display:flex;justify-content:space-between;align-items:center;min-height:48px;padding:0 1rem;border:1px solid var(--line);border-radius:12px;background:var(--surface);color:var(--accent);font-weight:700;text-decoration:none}",
  "a span{color:var(--muted);font-weight:400}a:focus-visible{outline:3px solid var(--accent);outline-offset:2px}",
].join("");

/** The download page for a fresh install: the current build and its APK. */
async function home(env, origin) {
  const { status, release } = await latestRelease(env, origin);
  if (status === 502) return badGateway();
  const files = (release?.assets || []).filter((a) => a.name.endsWith(".apk"));
  const title = release ? escapeHtml(release.name || release.tag_name) : "No release yet";
  const links = files
    .map((a) => `<li><a href="${escapeHtml(a.browser_download_url)}">Android APK<span>${(a.size / 1048576).toFixed(1)} MB</span></a></li>`)
    .join("");
  const html = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV Morning Alarm</title><style>${PAGE_CSS}</style>
<main><h1>TV Morning Alarm</h1><p>${title}</p><ul>${links}</ul></main></html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

const json = (body) => new Response(JSON.stringify(body), { headers: JSON_HEADERS });
const text = (body, status) => new Response(body, { status, headers: { "content-type": "text/plain" } });
const notFound = () => new Response(JSON.stringify({ message: "Not Found" }), { status: 404, headers: JSON_HEADERS });
const badGateway = () => new Response(JSON.stringify({ message: "GitHub unavailable" }), { status: 502, headers: JSON_HEADERS });
