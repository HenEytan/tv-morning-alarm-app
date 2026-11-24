import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const REPO = "HenEytan/tv-morning-alarm-app";
const TOKEN = "ghp_secret_never_leaks";
const SIGNED = "https://objects.githubusercontent.com/release-asset/app.apk?sig=abc";

const ghRelease = {
  url: `https://api.github.com/repos/${REPO}/releases/1`,
  tag_name: "build-42",
  name: "TV Morning Alarm build 42",
  assets: [
    {
      id: 7,
      name: "app-release-42.apk",
      size: 5_000_000,
      url: `https://api.github.com/repos/${REPO}/releases/assets/7`,
      browser_download_url: `https://github.com/${REPO}/releases/download/build-42/app-release-42.apk`,
    },
  ],
};

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

/** Stand in for api.github.com: `routes` maps a path to a Response factory; every call is recorded. */
function mockGithub(routes) {
  const calls = [];
  globalThis.fetch = async (input, init = {}) => {
    const u = new URL(typeof input === "string" ? input : input.url);
    calls.push({ url: u.href, init });
    assert.equal(u.origin, "https://api.github.com", "the token only ever goes to GitHub's API");
    const route = routes[u.pathname];
    if (!route) return new Response("{}", { status: 404 });
    return route(init);
  };
  return calls;
}

const okJson = (body) => () => new Response(JSON.stringify(body), { status: 200 });
const env = (extra = {}) => ({ REPO, TVALARM_GITHUB_TOKEN: TOKEN, ...extra });
const call = (path, init, e = env()) => worker.fetch(new Request(`https://feed.test${path}`, init), e);

const assetRoutes = {
  [`/repos/${REPO}/releases/tags/build-42`]: okJson(ghRelease),
  [`/repos/${REPO}/releases/assets/7`]: () => new Response(null, { status: 302, headers: { location: SIGNED } }),
};

test("latest proxies GitHub with the token and points every asset at this feed", async () => {
  const calls = mockGithub({ [`/repos/${REPO}/releases/latest`]: okJson(ghRelease) });
  const r = await call(`/repos/${REPO}/releases/latest`);
  assert.equal(r.status, 200);
  const raw = await r.text();
  assert.ok(!raw.includes(TOKEN));
  const body = JSON.parse(raw);
  assert.equal(body.tag_name, "build-42");
  assert.equal(body.html_url, "https://feed.test/");
  const feedUrl = `https://feed.test/repos/${REPO}/releases/assets/build-42/app-release-42.apk`;
  assert.equal(body.assets[0].url, feedUrl);
  assert.equal(body.assets[0].browser_download_url, feedUrl);
  assert.equal(body.assets[0].size, 5_000_000);
  assert.equal(calls[0].init.headers.Authorization, `Bearer ${TOKEN}`);
});

test("GITHUB_TOKEN is the fallback secret", async () => {
  const calls = mockGithub({ [`/repos/${REPO}/releases/latest`]: okJson(ghRelease) });
  const r = await call(`/repos/${REPO}/releases/latest`, undefined, { REPO, GITHUB_TOKEN: "other" });
  assert.equal(r.status, 200);
  assert.equal(calls[0].init.headers.Authorization, "Bearer other");
});

test("an asset 302s to GitHub's short-lived download URL", async () => {
  const calls = mockGithub(assetRoutes);
  const r = await call(`/repos/${REPO}/releases/assets/build-42/app-release-42.apk`);
  assert.equal(r.status, 302);
  assert.equal(r.headers.get("location"), SIGNED);
  const download = calls.find((c) => c.url.endsWith("/releases/assets/7"));
  assert.equal(download.init.headers.Accept, "application/octet-stream");
  assert.equal(download.init.redirect, "manual");
  assert.ok(![...r.headers.values()].some((v) => v.includes(TOKEN)));
});

test("an asset that is not on the release, or a release that does not exist, is 404", async () => {
  mockGithub(assetRoutes);
  assert.equal((await call(`/repos/${REPO}/releases/assets/build-42/other.apk`)).status, 404);
  assert.equal((await call(`/repos/${REPO}/releases/assets/build-1/app-release-42.apk`)).status, 404);
});

test("no token configured is 503, without calling GitHub", async () => {
  const calls = mockGithub({});
  assert.equal((await call(`/repos/${REPO}/releases/latest`, undefined, { REPO })).status, 503);
  assert.equal((await call(`/repos/${REPO}/releases/assets/build-42/a.apk`, undefined, { REPO })).status, 503);
  assert.equal(calls.length, 0);
});

test("GitHub failing is 502 and the body never carries the token", async () => {
  mockGithub({ [`/repos/${REPO}/releases/latest`]: () => new Response(`bad ${TOKEN}`, { status: 500 }) });
  const r = await call(`/repos/${REPO}/releases/latest`);
  assert.equal(r.status, 502);
  assert.ok(!(await r.text()).includes(TOKEN));

  globalThis.fetch = async () => {
    throw new Error(`network down ${TOKEN}`);
  };
  const t = await call(`/repos/${REPO}/releases/latest`);
  assert.equal(t.status, 502);
  assert.ok(!(await t.text()).includes(TOKEN));

  mockGithub({ ...assetRoutes, [`/repos/${REPO}/releases/assets/7`]: () => new Response("x", { status: 200 }) });
  assert.equal((await call(`/repos/${REPO}/releases/assets/build-42/app-release-42.apk`)).status, 502);
});

test("no release yet is 404", async () => {
  mockGithub({});
  assert.equal((await call(`/repos/${REPO}/releases/latest`)).status, 404);
});

test("another repository, unknown routes, traversal, bad escapes and writes are refused", async () => {
  const calls = mockGithub({ [`/repos/${REPO}/releases/latest`]: okJson(ghRelease) });
  assert.equal((await call("/repos/someone/else/releases/latest")).status, 404);
  assert.equal((await call(`/repos/${REPO}/releases`)).status, 404);
  assert.equal((await call(`/repos/${REPO}/releases/assets/7`)).status, 404);
  assert.equal((await call("/nope")).status, 404);
  assert.equal((await call(`/repos/${REPO}/releases/assets/..%2F..%2Fx/app.apk`)).status, 404);
  assert.equal((await call(`/repos/${REPO}/releases/assets/build-42/a%20b.apk`)).status, 404);
  assert.equal((await call("/%E0%A4%A")).status, 404);
  assert.equal((await call(`/repos/${REPO}/releases/latest`, { method: "POST" })).status, 405);
  assert.equal((await call(`/repos/${REPO}/releases/latest`, { method: "DELETE" })).status, 405);
  assert.equal(calls.length, 0);
});

test("the home page links the APK through this feed", async () => {
  mockGithub({ [`/repos/${REPO}/releases/latest`]: okJson(ghRelease) });
  const r = await call("/");
  assert.equal(r.status, 200);
  const html = await r.text();
  assert.ok(html.includes("TV Morning Alarm build 42"));
  assert.ok(html.includes(`https://feed.test/repos/${REPO}/releases/assets/build-42/app-release-42.apk`));
  assert.ok(!html.includes(TOKEN));
});
