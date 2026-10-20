import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";

const require = createRequire(import.meta.url);

// Point every plugin at a closed port so verification never reaches a live bot.
const settings = { botUrl: "http://127.0.0.1:9" };

function mockBdApi(seen) {
  globalThis.BdApi = {
    Logger: {
      info: (...args) => seen.logs.push(["info", ...args]),
      warn: (...args) => seen.logs.push(["warn", ...args]),
      error: (...args) => seen.logs.push(["error", ...args]),
    },
    UI: {
      showToast: (message, opts) => seen.toasts.push([message, opts?.type]),
    },
    Data: {
      load: (_plugin, key) => settings[key],
      save: () => {},
    },
  };
}

async function loadFactory(name) {
  const pluginPath = new URL(`../dist/${name}.plugin.js`, import.meta.url);
  const source = await readFile(pluginPath, "utf8");
  assert.match(source, new RegExp(`^/\\*\\*\\n \\* @name ${name}`, "m"), `${name}: BetterDiscord metadata header must be first`);
  assert.match(source, /module\.exports/, `${name}: bundle must assign CommonJS module.exports`);
  const factory = require(pluginPath.pathname);
  assert.equal(typeof factory, "function", `${name}: BetterDiscord export must be a meta => plugin factory`);
  return factory;
}

async function verifyBitchTracker() {
  const seen = { logs: [], toasts: [] };
  mockBdApi(seen);
  const factory = await loadFactory("BitchTracker");
  const instance = factory({ name: "BitchTracker", version: "0.0.1", description: "verify-bd-export smoke test" });
  assert.equal(typeof instance.start, "function", "plugin.start must exist");
  assert.equal(typeof instance.stop, "function", "plugin.stop must exist");

  const state = await instance.start();
  assert.equal(state.started, true, "start must mark plugin as started");
  assert.equal(typeof state.startedAt, "string", "start must record startedAt");
  instance.stop();
  assert.equal(state.started, false, "stop must mark plugin as stopped");
  assert.equal(typeof state.stoppedAt, "string", "stop must record stoppedAt");
  assert.ok(seen.logs.length >= 2, "lifecycle should log through BdApi.Logger when present");
  console.log("verified BitchTracker meta factory export");
}

async function verifyKnoxxLens() {
  const seen = { logs: [], toasts: [] };
  mockBdApi(seen);
  const factory = await loadFactory("KnoxxLens");
  const instance = factory({ name: "KnoxxLens", version: "0.0.1", description: "verify-bd-export smoke test" });
  assert.equal(typeof instance.start, "function", "plugin.start must exist");
  assert.equal(typeof instance.stop, "function", "plugin.stop must exist");
  assert.equal(typeof instance.getSettingsPanel, "function", "plugin.getSettingsPanel must exist");

  await instance.start();
  assert.ok(seen.logs.some(([level, name, msg]) => level === "info" && name === "KnoxxLens" && msg === "started"), "start must log through BdApi.Logger");
  instance.stop();
  assert.ok(seen.logs.some(([level, name, msg]) => level === "info" && name === "KnoxxLens" && msg === "stopped"), "stop must log through BdApi.Logger");
  console.log("verified KnoxxLens meta factory export");
}

await verifyBitchTracker();
await verifyKnoxxLens();
process.exit(0);
