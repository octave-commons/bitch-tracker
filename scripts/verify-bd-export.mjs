import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";

const require = createRequire(import.meta.url);
const pluginPath = new URL("../dist/BitchTracker.plugin.js", import.meta.url);
const source = await readFile(pluginPath, "utf8");

assert.match(source, /^\/\*\*\n \* @name BitchTracker/m, "BetterDiscord metadata header must be first");
assert.match(source, /module\.exports/, "bundle must assign CommonJS module.exports");

const seen = { logs: [], toasts: [] };
globalThis.BdApi = {
  Logger: {
    info: (...args) => seen.logs.push(["info", ...args]),
    warn: (...args) => seen.logs.push(["warn", ...args]),
    error: (...args) => seen.logs.push(["error", ...args]),
  },
  UI: {
    showToast: (message, opts) => seen.toasts.push([message, opts?.type]),
  },
};

const factory = require(pluginPath.pathname);
assert.equal(typeof factory, "function", "BetterDiscord export must be a meta => plugin factory");

const meta = {
  name: "BitchTracker",
  version: "0.0.1",
  description: "verify-bd-export smoke test",
};
const instance = factory(meta);
assert.equal(typeof instance.start, "function", "plugin.start must exist");
assert.equal(typeof instance.stop, "function", "plugin.stop must exist");

const state = await instance.start();
assert.equal(state.started, true, "start must mark plugin as started");
assert.equal(typeof state.startedAt, "string", "start must record startedAt");
instance.stop();
assert.equal(state.started, false, "stop must mark plugin as stopped");
assert.equal(typeof state.stoppedAt, "string", "stop must record stoppedAt");
assert.ok(seen.logs.length >= 2, "lifecycle should log through BdApi.Logger when present");

console.log("verified BetterDiscord meta factory export");
