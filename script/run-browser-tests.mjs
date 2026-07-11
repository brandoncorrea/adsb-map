// Drives the compiled CLJS browser-test build in real headless Chromium and
// maps the run's outcome to this process's exit code.
//
// Assumes `npx shadow-cljs compile test` has already produced
// target/browser-test (the `bb test:cljs` task does this first). We serve that
// directory over HTTP — shadow's generated index.html loads /js/test.js by an
// absolute path, so file:// will not do — launch Chromium, wait for the runner
// to park its result on window.adsbTestResult, and exit non-zero if anything
// failed or errored.
//
// See docs/testing-setup.md.

import { chromium } from "playwright";
import http from "node:http";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const ROOT = path.resolve("target/browser-test");

if (!existsSync(path.join(ROOT, "index.html"))) {
  console.error(
    `No compiled test build at ${ROOT}. Run 'npx shadow-cljs compile test' first.`,
  );
  process.exit(1);
}

const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
};

// A minimal static file server rooted at the compiled test build. Binding to
// port 0 lets the OS pick a free port, so this never contends with the dev-http
// server on 8290 or with another agent running the suite concurrently.
const server = http.createServer(async (req, res) => {
  try {
    let urlPath = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    if (urlPath === "/") urlPath = "/index.html";
    const filePath = path.normalize(path.join(ROOT, urlPath));
    if (!filePath.startsWith(ROOT) || !existsSync(filePath)) {
      res.statusCode = 404;
      res.end("not found");
      return;
    }
    const body = await readFile(filePath);
    res.setHeader(
      "Content-Type",
      CONTENT_TYPES[path.extname(filePath)] || "application/octet-stream",
    );
    res.end(body);
  } catch (err) {
    res.statusCode = 500;
    res.end(String(err));
  }
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const { port } = server.address();

const browser = await chromium.launch();
const page = await browser.newPage();

// Piping the browser console to stdout is the difference between a debuggable
// failure and a mystery. cljs.test writes its per-assertion output here.
page.on("console", (msg) => console.log(msg.text()));
page.on("pageerror", (err) => console.error("[page error]", err));

let exitCode = 1;
try {
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => window.adsbTestsDone === true, {
    timeout: 60_000,
  });

  const result = await page.evaluate(() => window.adsbTestResult);
  const pass = result?.pass ?? 0;
  const fail = result?.fail ?? 0;
  const error = result?.error ?? 0;

  console.log(`\n${pass} passed, ${fail} failed, ${error} errored`);
  exitCode = fail + error > 0 ? 1 : 0;
} catch (err) {
  console.error("browser test run failed:", err);
  exitCode = 1;
} finally {
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
}

process.exit(exitCode);
