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
