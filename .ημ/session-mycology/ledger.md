- ts: 2026-10-23T03:47:59.294412684Z
  session: /home/err/spaces/bitch-tracker
  task: Install full Discord suite on Yoga
  p-efficiency: 0.8
  p-friction: 0.55
  p-skill-candidate: 0.65
  spore: none
  receipt-refs: e827c12,bf0664f5-08ee-41a1-808b-287f2faf2506
  note: Actual Electron startup logs exposed remote ws mixed-content blocking and file-import restrictions; use persistent localhost transport relay and bundle theme tokens. External config edits require process restart because BetterDiscord caches and re-saves settings.
- ts: 2026-10-23T03:51:22.300207337Z
  session: /home/err/spaces/bitch-tracker
  task: Automatically repair BetterDiscord after Yoga Discord updates
  p-efficiency: 0.95
  p-friction: 0.15
  p-skill-candidate: 0.2
  spore: none
  receipt-refs: 707ec0b
  note: Reuse installed betterdiscordctl with status guard and systemd timer. install repairs without stopping Discord; reinstall may terminate it. Verify real timer recovery and healthy no-op behavior.
- ts: 2026-10-23T04:33:28.292391592Z
  session: /home/err/spaces/bitch-tracker
  task: Establish federated workstation suite lifecycle on Stealth and Yoga
  p-efficiency: 0.8
  p-friction: 0.5
  p-skill-candidate: 0.6
  spore: none
  receipt-refs: 2b4dd22,b40f431,de9ca9da
  note: Separate release publication from client convergence and machine/root policy. Preserve unknown local changes; validate staged bytes and test rollback. SCI FileLock close is not exposed: keep FileChannel open to own lock lifetime. Existing LAN SSH works; unattended tailnet SSH may need interactive reauthorization and must remain visible.
- ts: 2026-10-30T14:00:40.000Z
  session: /home/err/spaces/bitch-tracker
  task: Upgrade KnoxxLens topics (neural NER + POS + lens_refine_topics tool), fix Markdown streaming, and deduplicate suggested actions
  p-efficiency: 0.9
  p-friction: 0.25
  p-skill-candidate: 0.45
  spore: none
  receipt-refs: run:2dbc3a00-0894-45a2-b8e0-d422a851ea89
  note: Inspecting live trace_blocks first pinpointed why Knoxx's text-delta normalizer dropped whitespace-only deltas and caused terminal sync-assistant-message! to replay the full formatted pass. In Node >=22 shadow-cljs :node-test VM contexts, require("@huggingface/transformers") succeeds directly whereas Function("return import(...)") fails without an importModuleDynamically callback.
- ts: 2026-10-30T15:10:25.000Z
  session: /home/err/spaces/bitch-tracker
  task: Add KnoxxLens web link inspection (web.read / browser.read) and cross-channel/cross-server vector+graph context expansion (lens_grab_context)
  p-efficiency: 0.88
  p-friction: 0.35
  p-skill-candidate: 0.55
  spore: none
  receipt-refs: run:52ec912c-a697-4289-9baa-00b782074b5d
  note: Two sharp operational lessons: (1) When backfilling MongoDB collections that have both a deterministic string _id (`a||b||c`) and a compound unique secondary index (`source_node_id, target_node_id, edge_kind`), upsert filters must match the compound unique index (with `$setOnInsert: {_id}`) and deduplicate rows per batch, otherwise a delimiter mismatch (`|` vs `||`) or duplicate rows in an unordered bulkWrite triggers E11000 500s on live event flushes. (2) Knoxx `/ws/stream` wraps run events as `{channel: "events"|"tokens", payload: {...}}` with 20,000-char `result_preview` and sanitizes dotted tool names (`web.read` -> `web_read`).
- ts: 2026-11-02T01:43:00.000Z
  session: /home/err/spaces/bitch-tracker
  task: KnoxxLens v3 GovOne Sanitized Extraction, WebGL Connections Graph, Multimodal Browser Suite, Subagents, Live Telemetry & i3 Desktop Bridge
  p-efficiency: 0.92
  p-friction: 0.28
  p-skill-candidate: 0.58
  spore: none
  receipt-refs: knoxx-lens-v3-govone-webgl-connections-desktop-multimodal-complete
  note: Crucial architectural insights: (1) GovOne requires the WebSocket subprotocol "govone.realtime.v1" during HTTP Upgrade handshake, and crypto-shredding session DEKs is performed via realtime intervention {type: "intervene", payload: {session_id, action: "terminate"}} because HTTP DELETE is unhandled on /api/v1/sessions. (2) Discord.js v14 requires GatewayIntentBits Guilds/GuildMessages/DirectMessages/MessageContent plus Partials Channel/Message for DM reception. (3) @octave-commons/webgl-graph-view WebGLGraphView integrates seamlessly into shadow-cljs + esbuild bundles when installed as a local package and mounted into HTML5 canvases with golden-spiral node coordinates.
