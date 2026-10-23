# Yoga Discord suite installation

Installed and activated on 2026-10-22 (America/Chicago) from the working
Stealth distribution. The active suite is BitchTracker, KnoxxLens, and UXX
Discord. The archived OpenPlanner ingest/backfill predecessors are not installed
alongside their replacement.

## Runtime wiring

```text
Yoga Discord plugins
  -> http://127.0.0.1:7878
  -> Yoga user systemd socket relay
  -> Stealth Tailscale address 100.77.244.9:7878
  -> existing BitchTracker bot
     -> local OpenPlanner :7777
     -> Knoxx :18881 -> local Proxx :8789 -> local model
```

The localhost relay is necessary because Discord blocks a direct insecure
WebSocket connection to a remote private address as mixed content. Tailscale
carries the inter-device connection. There is no second bot process or copied
bot/provider key on Yoga.

## Installed files on Yoga

- `~/.config/BetterDiscord/plugins/BitchTracker.plugin.js`
- `~/.config/BetterDiscord/plugins/KnoxxLens.plugin.js`
- `~/.config/BetterDiscord/plugins/UXXDiscord.plugin.js`
- Corresponding BitchTracker/KnoxxLens plugin settings use
  `botUrl = http://127.0.0.1:7878`.
- `~/.config/BetterDiscord/data/stable/plugins.json` enables all three.
- `~/.config/systemd/user/bitch-tracker-broker.socket` listens on loopback.
- `~/.config/systemd/user/bitch-tracker-broker.service` uses the systemd socket
  proxy to connect to Stealth over Tailscale.

The user units are committed in Yoga's systemd repository on `device/yoga` as
`e827c12`. The socket is enabled; both socket and service were observed active.
The unrelated `barrier.service` edit was preserved.

Discord 1.0.166 had lost its BetterDiscord injection. The existing
`betterdiscordctl` restored injection in its desktop-core module using the
working BetterDiscord archive from Stealth. Discord was restarted to activate
it, and restarted again to clear BetterDiscord's cached former broker address.

UXX is self-contained: its installed CommonJS plugin bundles the workspace UXX
token exports using esbuild, replacing dynamic local-file/CDN imports. The
source is the working UXX companion and
`~/devel/orgs/open-hax/uxx/tokens/src/index.ts` on Stealth. No CDN is required.
An initial separate token module is retained at
`~/.config/BetterDiscord/uxx/tokens.mjs`, but the final plugin does not use it.

## Verification

Both product bundle hashes match Stealth exactly:

- BitchTracker: `8f89c30d560f4bb38bc7b48b2b6a753217e77fe4381957c982b169aa3ede7451`
- KnoxxLens: `35acdef2da2d4158ef8402dc78a4a4efaffee1eab01c74ac5b0f8b90e8477f96`

Yoga's actual Discord startup log after final activation shows:

- `[UXX Discord] Loaded theme: monokai`
- BitchTracker and KnoxxLens started.
- Two `[socket-client] connected` messages.
- BitchTracker's bot status is connected.
- No mixed-content or local-resource errors in that activation segment.

A synthetic ask originating on Yoga traversed the localhost relay, Tailscale,
the shared broker, Knoxx and local Proxx:

- Run `bf0664f5-08ee-41a1-808b-287f2faf2506`.
- Synthetic pinned message: Ada supplies code `YOGA-READY-7429`.
- Answer `YOGA-READY-7429`, 21.992 seconds.
- Accumulated streamed tokens exactly match completed text.
- Broker readiness reports `ready: true`, model `gemma4:e4b`.

During verification the shared bot was found pointing its OpenPlanner client at
an unavailable public Knoxx route. Restoring the existing local authority at
`http://127.0.0.1:7777` with its deployment credential restored successful
OpenPlanner batches (19, 9, 5, then 14). The repaired PM2 configuration was saved.
Credentials were not printed, committed, or sent to Yoga.

## Backups and maintenance

Yoga's pre-install BetterDiscord files and desktop-core index are retained at
`~/.local/share/bitch-tracker/backups/yoga-install-20261022/`.
The installation log is `~/.local/share/bitch-tracker/discord-install.log`.
Generated local build intermediates were retained in `/tmp/knoxxlens-yoga-*` on
Stealth; installed copies remain on Yoga. No prior artifacts were deleted.

A future Discord update may remove the loader again. Check
`betterdiscordctl status`, restore injection with the installed tool if needed,
and restart Discord. The relay requires Stealth and Tailscale to be available.
Open KnoxxLens with Ctrl+Shift+K.
