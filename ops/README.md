# Federated workstation suite

This is the first managed workstation profile for BitchTracker, KnoxxLens,
BetterDiscord and UXX. It is a running release/update mechanism for this suite,
not an assertion that the wider Foresight operating-system distribution exists.

## Ownership

- BitchTracker owns its client bundle release, validation, installation and status.
- Each device's systemd repository owns the installed units and their wiring.
- Ubuntu's native APT/unattended-upgrades services own security package changes.
- Knoxx and Proxx retain their existing runtimes and policy contracts. This
  updater does not rebuild containers, migrate databases or rotate credentials.
- The existing source checkouts and unrelated dirty files are never pulled,
  merged, reset or committed by a scheduled job.

## Release model

`pnpm suite:publish` publishes a new generation from the built distribution.
Build/verify the product first (`pnpm build`); publishing checks plugin syntax,
the BetterDiscord export/lifecycle smoke checks and the federation tests.
The package comprises the three plugin files, BetterDiscord archive, updater
implementation and a candidate Ubuntu security policy. Every payload has a
SHA-256 digest; the generation id hashes the canonical digest map. Source HEAD
and the tracked working diff hash remain in the release manifest.

The channel is `~/.local/share/bitch-tracker/channel/stable.edn`. Immutable
payload generations and their manifests stay in `channel/releases/`. Publishing
writes the channel pointer last. It is a local qualified-artifact channel, not a
GitHub latest-commit subscription and not a signed binary transparency log.
It relies on the enrolled account, filesystem permissions and verified SSH host
keys. Provider/model routing and timing remain Proxx policy, never updater logic.

Stealth distributes the selected generation every five minutes and triggers the
peer updater. Yoga independently checks its inbox every five minutes. Peers
retain working versions when the coordinator or network is offline. Each profile
is EDN (`profiles/`), and machine-specific plugin settings/conversations stay
local. Both profiles currently use the shared backend on Stealth; compute/service
failover is not part of this release.

## Safe convergence

All payloads are validated before installing anything, and staged copies are
hashed again before activation. Destinations are a fixed allowlist below the
account home. An unknown local modification holds the whole generation and
asks for review. Existing files are backed up before replacement; a write failure
restores files already changed. Backups and old generations are retained.

`enroll` is a one-time, explicit adoption operation; scheduled checks never use
it to override local edits. Updater libraries are versioned in the release too.
Root security configuration is delivered as a candidate under the account home;
future changes require administrator review and produce a notification rather
than silently acquiring root privileges.

BetterDiscord's repair timer runs every 30 seconds on both machines and uses
its existing installer only when the loader is missing. It can repair without
interrupting the running Discord process. Installing a new framework archive
creates a restart notice that persists until the Discord process changes.

## Human input and observations

The user status file is `~/.local/state/bitch-tracker/status.edn`. It records
installed generation, check result, backups and outstanding attention items.
Desktop notifications are deduplicated per issue and repeated daily while an
issue remains outstanding. Unavailable desktop notifications stay pending for
later checks. Stealth also collects peer status in `~/.local/state/bitch-tracker/peers/` and mirrors the peer's attention notices.

Reported conditions include release corruption, local file changes, a failed
repair/update service, unfinished package configuration, failed automatic
security updates, required reboots, non-security package updates and root policy
changes. Three consecutive peer-sync failures trigger a connectivity/SSH notice.
No automatic reboot or forced Discord restart is configured.

Use `pnpm suite:status` on Stealth for local and collected peer evidence. The
snapshots have timestamps; old peer state must not be mistaken for a live probe.
The systemd user timers and journals are the executable scheduling authority;
no Codex heartbeat or separate scheduler is required.

## Ubuntu policy

`apt/52federated-suite-security` enables daily package-list refresh and automatic
security upgrades, retains the existing Ubuntu security/ESM and vendor-origin policies, and
explicitly disables automatic reboots. It was
installed in `/etc/apt/apt.conf.d/` on both machines, preserving a copy in the
suite payload. The native apt-daily and apt-daily-upgrade timers stay enabled.
Non-security upgrades are reported for review.

## Tests and scope

`pnpm suite:test` exercises path/manifest rejection, local-change holds,
explicit enrollment, corrupted payloads, generation convergence, exact backups,
rollback on a simulated mid-install write failure, and notification cadence.

The next distro-level work belongs in Foresight and its owning products: a
reviewed release promotion pipeline, signed release metadata, shared unit/profile
rendering through shx/Services, service upgrade/migration admission, backup
qualification and host enrollment/revocation. This profile does not duplicate
those kernels or claim unattended source merging is safe.
