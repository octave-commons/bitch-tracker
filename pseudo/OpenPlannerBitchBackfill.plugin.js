/**
 * @name OpenPlanner Bitch Backfill
 * @author open-hax
 * @description Backfills messages from known bitch users into the bitch tracker channel.
 * @version 1.0.1
 */

const CONFIG = {
  bitchTrackerChannelId: "1503465577132462130",
  knownBitchUserIds: new Set([
    "59259128266100736",
    "376762142910578692",
    "440099490364391435",
    "1441420406711124169",
    "281812122445283330",
  ]),
  backfillDays: 7,
  batchSize: 100,
  dryRun: false,
  poodleEmoji: "🐩",
  clownEmoji: "🤡",
  // Throttling config
  channelDelayMs: 1500,
  messageDelayMs: 1000,
};

module.exports = class OpenPlannerBitchBackfill {
  constructor() {
    this._dispatcher = null;
    this._channelStore = null;
    this._guildStore = null;
    this._messageStore = null;
    this._userStore = null;
    this._running = false;
  }

  start() {
    this._loadModules();
    console.log("[Bitch Backfill] Plugin loaded. Click 'Backfill Now' in plugin settings to run.");
  }

  stop() {
    this._running = false;
    console.log("[Bitch Backfill] Stopped");
  }

  getSettingsPanel() {
    const root = document.createElement("div");
    root.style.cssText = "padding:16px;display:flex;flex-direction:column;gap:12px;color:var(--text-normal);";

    const status = this._running ? "Running..." : "Idle";
    const trackedCount = CONFIG.knownBitchUserIds.size;

    root.append(
      this._note(`Status: ${status}`),
      this._note(`Known bitches: ${trackedCount} users`),
      this._note(`Tracker channel: ${CONFIG.bitchTrackerChannelId}`),
      this._note(`Backfill window: ${CONFIG.backfillDays} days`),
      this._field("Moderation watch terms", "watchTerms", this._setting("watchTerms", ""), "Comma-separated literal terms or /regex/i patterns"),
      this._note("Click 'Backfill Now' to start."),
      this._button("Backfill Now", () => this._runBackfill()),
      this._button("Test Tracker Send", () => this._sendRawTrackerMessage("[backfill-test] tracker send test")),
    );

    return root;
  }

  _loadModules() {
    const Webpack = BdApi.Webpack;
    this._dispatcher = Webpack.getModule(m => m?.dispatch && m?.subscribe && m?.unsubscribe, { searchExports: true });
    this._channelStore = Webpack.getStore?.("ChannelStore") ?? Webpack.getModule(m => m?.getChannel && m?.getDMFromUserId, { searchExports: true });
    this._guildStore = Webpack.getStore?.("GuildStore") ?? Webpack.getModule(m => m?.getGuild && m?.getGuilds, { searchExports: true });
    this._messageStore = Webpack.getStore?.("MessageStore") ?? Webpack.getModule(m => m?.getMessage && m?.getMessages, { searchExports: true });
    this._userStore = Webpack.getStore?.("UserStore") ?? Webpack.getModule(m => m?.getCurrentUser && m?.getUser, { searchExports: true });
  }

  async _runBackfill() {
    if (this._running) {
      BdApi.UI?.showToast?.("Backfill already running", { type: "warning" });
      return;
    }

    this._running = true;
    BdApi.UI?.showToast?.("Starting bitch backfill...", { type: "info" });
    console.log("[Bitch Backfill] ============================================");
    console.log("[Bitch Backfill] Starting backfill process");

    try {
      const trackerChannel = this._channelStore?.getChannel?.(CONFIG.bitchTrackerChannelId);
      if (!trackerChannel) {
        console.error("[Bitch Backfill] Tracker channel not found:", CONFIG.bitchTrackerChannelId);
        BdApi.UI?.showToast?.("Tracker channel not found", { type: "error" });
        return;
      }
      console.log("[Bitch Backfill] Tracker channel found:", trackerChannel.name);

      const since = Date.now() - (CONFIG.backfillDays * 24 * 60 * 60 * 1000);
      console.log("[Bitch Backfill] Looking for messages since:", new Date(since).toISOString());

      let totalFound = 0;
      let totalSent = 0;

      // Get all loaded channels
      const channels = this._getRelevantChannels();
      console.log(`[Bitch Backfill] Will scan ${channels.length} channels`);

      for (let i = 0; i < channels.length; i++) {
        if (!this._running) {
          console.log("[Bitch Backfill] Stopped early");
          break;
        }

        const channel = channels[i];
        console.log(`[Bitch Backfill] [${i + 1}/${channels.length}] Processing #${channel?.name ?? channel.id}`);

        try {
          const messages = await this._fetchMessagesFromChannel(channel.id, since);
          console.log(`[Bitch Backfill] [${i + 1}/${channels.length}] Got ${messages.length} messages from #${channel?.name ?? channel.id}`);

          const bitchMessages = messages.filter(msg => {
            const authorId = this._authorId(msg);
            const isKnownBitch = CONFIG.knownBitchUserIds.has(authorId);
            const isPoodled = this._hasPoodleReaction(msg);
            const moderationHits = this._moderationHits(msg.content);
            const isBitch = isKnownBitch || isPoodled || moderationHits.length > 0;
            if (isBitch) {
              msg.__openplannerModerationHits = moderationHits;
              console.log(`[Bitch Backfill] Found bitch message from ${authorId} known=${isKnownBitch} poodled=${isPoodled} watch=${moderationHits.join("|")}:`, msg.content?.substring(0, 50));
            }
            return isBitch;
          });

          console.log(`[Bitch Backfill] [${i + 1}/${channels.length}] Found ${bitchMessages.length} bitch messages`);
          if (messages.length > 0 && bitchMessages.length === 0) {
            const sampleAuthors = [...new Set(messages.slice(0, 10).map(msg => this._authorId(msg)).filter(Boolean))];
            console.log(`[Bitch Backfill] No known-bitch matches in #${channel?.name ?? channel.id}; sample author IDs:`, sampleAuthors);
          }
          totalFound += bitchMessages.length;

          for (let j = 0; j < bitchMessages.length; j++) {
            if (!this._running) break;
            const message = bitchMessages[j];
            console.log(`[Bitch Backfill] [${i + 1}/${channels.length}] Sending bitch message ${j + 1}/${bitchMessages.length}`);
            await this._sendToBitchTracker(message, channel);
            totalSent++;
            console.log(`[Bitch Backfill] Sent ${totalSent}/${totalFound} so far`);

            // Throttle between messages
            if (j < bitchMessages.length - 1) {
              console.log(`[Bitch Backfill] Waiting ${CONFIG.messageDelayMs}ms before next message...`);
              await this._sleep(CONFIG.messageDelayMs);
            }
          }
        } catch (channelErr) {
          console.error(`[Bitch Backfill] Error processing channel ${channel?.name ?? channel.id}:`, channelErr);
        }

        // Throttle between channels
        if (i < channels.length - 1) {
          console.log(`[Bitch Backfill] Waiting ${CONFIG.channelDelayMs}ms before next channel...`);
          await this._sleep(CONFIG.channelDelayMs);
        }
      }

      console.log(`[Bitch Backfill] ============================================`);
      console.log(`[Bitch Backfill] COMPLETE: ${totalSent}/${totalFound} messages sent`);
      BdApi.UI?.showToast?.(
        `Backfill complete: ${totalSent}/${totalFound} messages sent`,
        { type: "success", timeout: 5000 }
      );
    } catch (err) {
      console.error("[Bitch Backfill] Fatal error:", err);
      BdApi.UI?.showToast?.("Backfill failed: " + err.message, { type: "error" });
    } finally {
      this._running = false;
    }
  }

  _getRelevantChannels() {
    const channels = [];

    // Get all guild channels
    const guildIds = this._guildStore?.getGuilds?.() ?? {};
    console.log(`[Bitch Backfill] Found ${Object.keys(guildIds).length} guilds`);
    for (const guildId of Object.keys(guildIds)) {
      const guild = this._guildStore?.getGuild?.(guildId);
      if (!guild) continue;

      const guildChannels = this._channelStore?.getMutableGuildChannelsForGuild?.(guildId);
      if (guildChannels) {
        const textChannels = Object.values(guildChannels).filter(ch => ch?.type === 0);
        console.log(`[Bitch Backfill] Guild "${guild.name}" (${guildId}): ${textChannels.length} text channels`);
        channels.push(...textChannels);
      }
    }

    // Also check DM channels
    const privateChannels = this._channelStore?.getMutablePrivateChannels?.() ?? {};
    const dmList = Object.values(privateChannels);
    console.log(`[Bitch Backfill] Found ${dmList.length} DM channels`);
    channels.push(...dmList);

    console.log(`[Bitch Backfill] Total channels to scan: ${channels.length}`);
    return channels;
  }

  async _fetchMessagesFromChannel(channelId, since) {
    try {
      const channel = this._channelStore?.getChannel?.(channelId);
      console.log(`[Bitch Backfill] Fetching messages from #${channel?.name ?? channelId}...`);

      // Try to load more messages via Discord's API
      const MessageActions = BdApi.Webpack.getModule(
        m => m?.fetchMessages || m?.loadMessages,
        { searchExports: true }
      );

      if (MessageActions?.fetchMessages) {
        console.log(`[Bitch Backfill] Calling fetchMessages for ${channelId}...`);
        try {
          await MessageActions.fetchMessages({ channelId, limit: CONFIG.batchSize });
          console.log(`[Bitch Backfill] fetchMessages returned for ${channelId}`);
        } catch (fetchErr) {
          console.warn(`[Bitch Backfill] fetchMessages error for ${channelId}:`, fetchErr);
        }
      } else {
        console.log(`[Bitch Backfill] No fetchMessages module found`);
      }

      // Get messages from store
      const messages = this._messageStore?.getMessages?.(channelId);
      if (!messages) {
        console.log(`[Bitch Backfill] No messages in MessageStore for channel ${channelId}`);
        return [];
      }

      console.log(`[Bitch Backfill] MessageStore returned type: ${typeof messages}, constructor: ${messages?.constructor?.name}`);

      let messageArray;
      if (Array.isArray(messages)) {
        messageArray = messages;
      } else if (messages.toArray) {
        messageArray = messages.toArray();
      } else if (messages._messages) {
        messageArray = messages._messages;
      } else {
        console.log(`[Bitch Backfill] Unknown message store structure, keys:`, Object.keys(messages));
        messageArray = [];
      }

      console.log(`[Bitch Backfill] Found ${messageArray.length} total messages in #${channel?.name ?? channelId}`);

      const filtered = messageArray.filter(msg => {
        if (!msg) return false;
        const timestamp = new Date(msg.timestamp ?? 0).getTime();
        return timestamp >= since;
      });

      console.log(`[Bitch Backfill] ${filtered.length} messages within ${CONFIG.backfillDays} days`);
      return filtered;
    } catch (err) {
      console.warn(`[Bitch Backfill] Failed to fetch messages for ${channelId}:`, err);
      return [];
    }
  }

  async _sendToBitchTracker(message, channel) {
    console.log("[Bitch Backfill] _sendToBitchTracker called");

    const trackerChannelId = CONFIG.bitchTrackerChannelId;
    if (!trackerChannelId) {
      console.warn("[Bitch Backfill] No tracker channel configured, skipping send");
      return;
    }

    const content = message.content ?? "";
    const author = message.author;
    const authorName = author?.username ?? author?.globalName ?? "Unknown";
    const authorId = this._authorId(message);
    const guildId = channel?.guild_id ?? "@me";
    const guild = guildId === "@me" ? null : this._guildStore?.getGuild?.(guildId);
    const guildName = guild?.name ?? channel?.guild?.name ?? channel?.guildName ?? "unknown server";
    const messageLink = `https://discord.com/channels/${guildId}/${channel.id}/${message.id}`;
    const messageTs = this._discordTimestamp(message.timestamp ?? message.timestamp?._i);
    const detectedTs = this._discordTimestamp(Date.now());
    const moderationHits = message.__openplannerModerationHits ?? [];

    const trackerMessage = [
      `**[backfill]** Bitch activity detected`,
      `**Author:** ${authorName} (${authorId})`,
      `**Server:** ${guildName} (${guildId})`,
      `**Channel:** #${channel?.name ?? "unknown"}`,
      `**Message timestamp:** ${messageTs}`,
      `**Detected:** ${detectedTs}`,
      ...(moderationHits.length > 0 ? [`**Matched watch terms:** ${moderationHits.join(", ")}`] : []),
      `**Message:** ${content || "(no text content)"}`,
      `**Link:** ${messageLink}`,
    ].join("\n");

    if (CONFIG.dryRun) {
      console.log("[Bitch Backfill] DRY RUN - would send:", trackerMessage.substring(0, 100));
      return;
    }

    try {
      console.log(`[Bitch Backfill] Attempting to send message to ${trackerChannelId}`);

      await this._sendRawTrackerMessage(trackerMessage);
    } catch (err) {
      console.error("[Bitch Backfill] Failed to send tracker message:", err);
    }
  }

  async _sendRawTrackerMessage(content) {
    const channelId = CONFIG.bitchTrackerChannelId;
    console.log(`[Bitch Backfill] _sendRawTrackerMessage called for ${channelId}`);
    if (!channelId) return;
    const body = { content, nonce: this._nonce(), tts: false };

    const Http = BdApi.findModuleByProps?.("get", "post", "put", "del")
      || BdApi.Webpack.getModule(m => m?.post && m?.get && typeof m.post === "function", { searchExports: true })
      || BdApi.Webpack.getModule(m => m?.HTTP?.post && typeof m.HTTP.post === "function", { searchExports: true })?.HTTP;
    const Endpoints = BdApi.findModuleByProps?.("Endpoints")?.Endpoints
      || BdApi.Webpack.getModule(m => m?.Endpoints?.MESSAGES, { searchExports: true })?.Endpoints;
    const url = Endpoints?.MESSAGES ? Endpoints.MESSAGES(channelId) : `/channels/${channelId}/messages`;
    if (Http?.post) {
      console.log("[Bitch Backfill] Sending via Discord HTTP module");
      try {
        await Http.post({ url, body });
        return;
      } catch (err) {
        console.warn("[Bitch Backfill] HTTP module send failed, trying fetch fallback", err);
      }
    }

    console.log("[Bitch Backfill] Trying fetch fallback");
    const authModule = BdApi.findModuleByProps?.("getToken") || BdApi.Webpack.getModule(m => m?.getToken && typeof m.getToken === "function", { searchExports: true });
    const authToken = authModule?.getToken?.();
    if (authToken) {
      try {
        const fetchImpl = BdApi.Net?.fetch ?? fetch;
        const res = await fetchImpl(`https://discord.com/api/v9/channels/${channelId}/messages`, {
          method: "POST",
          headers: { Authorization: authToken, "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
        return;
      } catch (err) {
        console.warn("[Bitch Backfill] fetch send failed, trying MessageActions fallback", err);
      }
    } else {
      console.warn("[Bitch Backfill] No auth token available for fetch fallback");
    }

    const MessageActions = BdApi.findModuleByProps?.("sendMessage", "editMessage", "deleteMessage")
      || BdApi.Webpack.getModule(m => m?.sendMessage && typeof m.sendMessage === "function", { searchExports: true });
    if (MessageActions?.sendMessage) {
      const attempts = [
        () => MessageActions.sendMessage(channelId, body, undefined, { nonce: body.nonce }),
        () => MessageActions.sendMessage(channelId, body, undefined, undefined, { nonce: body.nonce }),
        () => MessageActions.sendMessage(channelId, body, { nonce: body.nonce }),
      ];
      for (const attempt of attempts) {
        try {
          console.log("[Bitch Backfill] Sending via Discord MessageActions.sendMessage fallback");
          await attempt();
          return;
        } catch (err) {
          console.warn("[Bitch Backfill] MessageActions attempt failed", err);
        }
      }
    }

    throw new Error("No Discord send path succeeded");
  }

  _sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  _nonce() {
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  _discordTimestamp(value) {
    const date = value ? new Date(value) : new Date();
    const ms = Number.isNaN(date.getTime()) ? Date.now() : date.getTime();
    return `<t:${Math.floor(ms / 1000)}:F>`;
  }

  _authorId(message) {
    const author = message?.author;
    if (typeof author === "string" || typeof author === "number") return String(author);
    return String(
      author?.id
      ?? message?.authorId
      ?? message?.author_id
      ?? message?.userId
      ?? message?.user_id
      ?? ""
    );
  }

  _hasPoodleReaction(message) {
    const reactions = message?.reactions;
    const arr = Array.isArray(reactions) ? reactions : [...(reactions?.values?.() ?? [])];
    return arr.some(reaction => {
      const emoji = reaction?.emoji;
      return this._isBitchEmoji(emoji)
        || this._isBitchEmoji(emoji?.name)
        || this._isBitchEmoji(reaction?.emojiName)
        || this._isBitchEmoji(reaction?.name);
    });
  }

  _isBitchEmoji(emoji) {
    return [CONFIG.poodleEmoji, CONFIG.clownEmoji].includes(String(emoji ?? ""));
  }

  _moderationHits(text) {
    const value = String(text ?? "");
    if (!value.trim()) return [];
    return this._watchPatterns()
      .filter(({ pattern }) => pattern.test(value))
      .map(({ label }) => label);
  }

  _watchPatterns() {
    return this._watchTerms()
      .filter(Boolean)
      .map(term => this._watchPattern(term))
      .filter(Boolean);
  }

  _watchTerms() {
    const settingsTerms = String(this._setting("watchTerms", ""))
      .split(",")
      .map(term => term.trim())
      .filter(Boolean);
    const config = this._watchConfig();
    return [
      ...settingsTerms,
      ...(Array.isArray(config.watchTerms) ? config.watchTerms : []),
      ...(Array.isArray(config.watchRegexes) ? config.watchRegexes : []),
    ].map(term => String(term).trim()).filter(Boolean);
  }

  _watchConfig() {
    try {
      const fs = require("fs");
      const file = "/home/err/snap/discord/278/.config/BetterDiscord/plugins/OpenPlannerModerationWatchlist.json";
      if (!fs.existsSync(file)) return {};
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch (err) {
      console.warn("[Bitch Backfill] Failed to read moderation watchlist config", err);
      return {};
    }
  }

  _watchPattern(term) {
    const regexMatch = term.match(/^\/(.*)\/([a-z]*)$/i);
    if (regexMatch) {
      try {
        return { label: term, pattern: new RegExp(regexMatch[1], regexMatch[2].includes("i") ? regexMatch[2] : `${regexMatch[2]}i`) };
      } catch (_err) {
        return null;
      }
    }
    return { label: term, pattern: new RegExp(`\\b${this._escapeRegExp(term)}\\b`, "i") };
  }

  _escapeRegExp(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  _setting(key, fallback) {
    return BdApi.Data.load("OpenPlannerBitchBackfill", key) ?? fallback;
  }

  _saveSetting(key, value) {
    BdApi.Data.save("OpenPlannerBitchBackfill", key, value);
  }

  _note(text) {
    const el = document.createElement("div");
    el.style.cssText = "font-size:12px;color:var(--text-muted);line-height:1.4;";
    el.textContent = text;
    return el;
  }

  _field(labelText, key, value, placeholder) {
    const wrap = document.createElement("label");
    wrap.style.cssText = "display:flex;flex-direction:column;gap:6px;font-weight:600;";
    const label = document.createElement("span");
    label.textContent = labelText;
    const input = document.createElement("input");
    input.type = "text";
    input.value = value;
    input.placeholder = placeholder;
    input.style.cssText = "padding:8px 10px;border-radius:6px;border:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-normal);";
    input.addEventListener("change", () => this._saveSetting(key, input.value));
    wrap.append(label, input);
    return wrap;
  }

  _button(label, onClick) {
    const btn = document.createElement("button");
    btn.textContent = label;
    btn.style.cssText = "padding:10px 16px;border-radius:6px;border:none;background:var(--button-background);color:var(--button-text);cursor:pointer;";
    btn.addEventListener("click", onClick);
    return btn;
  }
};
