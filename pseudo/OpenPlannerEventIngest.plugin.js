/**
 * @name OpenPlanner Event Ingest
 * @author open-hax
 * @description Mirrors Discord messages from allowlisted guilds into OpenPlanner /v1/events with reaction-based labeling and bitch classification.
 * @version 1.0.0
 */

const CONFIG = {
  defaultBaseUrl: "http://127.0.0.1:7777",
  defaultProject: "discord",
  maxBatchSize: 25,
  flushEveryMs: 1500,
  retryEveryMs: 10000,
  maxPersistedEvents: 500,
  guildIds: new Set([
    "1228232798448390144",
    "1391832426048651334",
    "974519864045756446",
    "1128867683291627614",
    "244230771232079873",
      "1425557239808393418"
  ]),
  // Bitch classifier configuration
  bitchTrackerChannelId: "1503465577132462130",
  bitchWatchChannelId: "1503466522666995892",
  poodleEmoji: "🐩",
  clownEmoji: "🤡",
  bitchThreshold: 3,
  defaultSemanticQueryInstruction: "Represent the Discord message for semantic retrieval of similar moderation incidents: sexist, racist, transphobic, ableist, antisemitic, misgendering, harassment, or 'just joking' bigotry. Retrieve messages with similar abusive social behavior and intent, not merely exact wording.",
  knownBitchUserIds: new Set([
    "59259128266100736",
    "376762142910578692",
    "440099490364391435",
    "1441420406711124169",
    "281812122445283330",
      "853343486756388944"
  ]),
};

module.exports = class OpenPlannerEventIngest {
  constructor() {
    this._dispatcher = null;
    this._channelStore = null;
    this._guildStore = null;
    this._userStore = null;
    this._messageStore = null;
    this._queue = [];
    this._seen = new Set();
    this._flushTimer = null;
    this._retryTimer = null;
    this._boundMessageCreate = this._onMessageCreate.bind(this);
    this._boundReactionAdd = this._onReactionAdd.bind(this);
    this._boundReactionRemove = this._onReactionRemove.bind(this);
    this._bitchCounts = new Map(); // userId -> count
    this._bitchMessages = new Map(); // userId -> Set<messageId>
    this._labeledMessages = new Set(); // messageIds that have been labeled as bitch
    this._pendingSemanticQueries = new Set(); // messageIds waiting for embedding before semantic query
    this._semanticScanTimer = null;
    this._loadBitchState();
  }

  start() {
    this._loadModules();
    this._loadPersistedQueue();

    if (!this._dispatcher?.subscribe) {
      console.error("[OpenPlanner Event Ingest] Could not find Discord Dispatcher.subscribe");
      BdApi.UI?.showToast?.("OpenPlanner ingest: Dispatcher not found", { type: "error" });
      return;
    }

    this._dispatcher.subscribe("MESSAGE_CREATE", this._boundMessageCreate);
    this._dispatcher.subscribe("MESSAGE_REACTION_ADD", this._boundReactionAdd);
    this._dispatcher.subscribe("MESSAGE_REACTION_REMOVE", this._boundReactionRemove);
    this._flushTimer = setInterval(() => this._flush(), CONFIG.flushEveryMs);
    this._retryTimer = setInterval(() => this._flush(), CONFIG.retryEveryMs);
    this._semanticScanTimer = setInterval(() => this._runSemanticScan(), 30000); // scan every 30s

    console.log("[OpenPlanner Event Ingest] Started for guilds:", [...CONFIG.guildIds]);
    BdApi.UI?.showToast?.("OpenPlanner ingest started", { type: "success" });
  }

  stop() {
    try {
      this._dispatcher?.unsubscribe?.("MESSAGE_CREATE", this._boundMessageCreate);
      this._dispatcher?.unsubscribe?.("MESSAGE_REACTION_ADD", this._boundReactionAdd);
      this._dispatcher?.unsubscribe?.("MESSAGE_REACTION_REMOVE", this._boundReactionRemove);
    } catch (err) {
      console.warn("[OpenPlanner Event Ingest] unsubscribe failed", err);
    }

    if (this._flushTimer) clearInterval(this._flushTimer);
    if (this._retryTimer) clearInterval(this._retryTimer);
    if (this._semanticScanTimer) clearInterval(this._semanticScanTimer);
    this._flushTimer = null;
    this._retryTimer = null;
    this._semanticScanTimer = null;
    this._persistQueue();
    this._persistBitchState();
    console.log("[OpenPlanner Event Ingest] Stopped");
  }

  getSettingsPanel() {
    const root = document.createElement("div");
    root.style.cssText = "padding:16px;display:flex;flex-direction:column;gap:12px;color:var(--text-normal);";

    const endpoint = this._endpoint();
    const savedApiKey = BdApi.Data.load("OpenPlannerEventIngest", "apiKey") ?? "";
    const project = this._setting("project", CONFIG.defaultProject);
    const keySource = savedApiKey ? "BetterDiscord settings" : (this._apiKey() ? "$OPENPLANNER_API_KEY" : "missing");

    const bitchCount = this._bitchCounts.size;
    const labeledCount = this._labeledMessages.size;
    const botConfigStatus = this._botConfigStatus();

    root.append(
      this._field("OpenPlanner /v1/events endpoint", "endpoint", endpoint, "Derived from $OPENPLANNER_BASE_URL when unset"),
      this._field("OpenPlanner API key override", "apiKey", savedApiKey, "Leave blank to use $OPENPLANNER_API_KEY", true),
      this._field("OpenPlanner project", "project", project, "discord"),
      this._field("Moderation watch terms", "watchTerms", this._setting("watchTerms", ""), "Comma-separated literal terms or /regex/i patterns"),
      this._field("Semantic query instruction", "semanticQueryInstruction", this._setting("semanticQueryInstruction", CONFIG.defaultSemanticQueryInstruction), "Instruction prefix for bitch-watch memory searches"),
      this._note(`API key source: ${keySource}`),
      this._note(`Allowlisted guild IDs: ${[...CONFIG.guildIds].join(", ")}`),
      this._note(`Bitch tracker channel: ${CONFIG.bitchTrackerChannelId}`),
      this._note(`Bitch watch channel: ${CONFIG.bitchWatchChannelId}`),
      this._note(botConfigStatus),
      this._note(`Tracked bitches: ${bitchCount} users, ${labeledCount} labeled messages`),
      this._note("Only MESSAGE_CREATE and MESSAGE_REACTION_ADD events seen by this client while the plugin is enabled are mirrored. It does not backfill history.")
    );

    return root;
  }

  // ── Discord Module Loading ────────────────────────────────────────────────

  _loadModules() {
    const Webpack = BdApi.Webpack;
    this._dispatcher = Webpack.getModule(m => m?.dispatch && m?.subscribe && m?.unsubscribe, { searchExports: true });
    this._channelStore = Webpack.getStore?.("ChannelStore") ?? Webpack.getModule(m => m?.getChannel && m?.getDMFromUserId, { searchExports: true });
    this._guildStore = Webpack.getStore?.("GuildStore") ?? Webpack.getModule(m => m?.getGuild && m?.getGuilds, { searchExports: true });
    this._userStore = Webpack.getStore?.("UserStore") ?? Webpack.getModule(m => m?.getCurrentUser && m?.getUser, { searchExports: true });
    this._messageStore = Webpack.getStore?.("MessageStore") ?? Webpack.getModule(m => m?.getMessage && m?.getMessages, { searchExports: true });
  }

  // ── Event Handlers ────────────────────────────────────────────────────────

  _onMessageCreate(payload) {
    const message = payload?.message ?? payload;
    if (!message?.id || !message?.channel_id) return;
    if (message.state === "SENDING" || message.type === 8) return;

    const channel = this._channelStore?.getChannel?.(message.channel_id);
    const guildId = String(message.guild_id ?? channel?.guild_id ?? channel?.getGuildId?.() ?? "");
    if (!CONFIG.guildIds.has(guildId)) return;

    const authorId = String(message.author?.id ?? message.author_id ?? "");
    const botUserId = this._botUserId();
    if (botUserId && authorId === botUserId) return;

    const event = this._messageToEvent(message, channel, guildId);
    if (!event || this._seen.has(event.id)) return;

    this._seen.add(event.id);
    this._queue.push(event);

    // If message is from a known bitch, send to bitch tracker for review
    const content = typeof message.content === "string" ? message.content : "";
    if (CONFIG.knownBitchUserIds.has(authorId)) {
      this._sendToBitchTracker(message, channel, guildId, "known-bitch-user");
      if (content.trim()) this._queueBitchWatchSemanticSearch(authorId, message);
    }

    const moderationHits = this._moderationHits(content);
    if (moderationHits.length > 0) {
      this._sendToModerationTracker(message, channel, guildId, moderationHits);
    }

    this._persistQueue();

    if (this._queue.length >= CONFIG.maxBatchSize) void this._flush();
  }

  _onReactionAdd(payload) {
    const reaction = payload;
    if (!reaction?.messageId || !reaction?.channelId) return;

    const channel = this._channelStore?.getChannel?.(reaction.channelId);
    const guildId = String(reaction.guildId ?? channel?.guild_id ?? channel?.getGuildId?.() ?? "");
    if (!CONFIG.guildIds.has(guildId)) return;

    // Discord sends unicode emoji in .name, custom emoji may be in .id or .name
    const emojiName = reaction.emoji?.name ?? "";
    const emojiId = reaction.emoji?.id ?? "";
    // For custom emoji the format is <:name:id>; for unicode it's just the character
    const emoji = emojiName || emojiId;
    const userId = String(reaction.userId ?? "");
    const messageId = String(reaction.messageId ?? "");
    const channelId = String(reaction.channelId ?? "");

    // Debug logging for emoji reactions
    if (emojiName || emojiId) {
      console.log(`[Reaction Debug] emojiName="${emojiName}" emojiId="${emojiId}" full="${emoji}"`);
    }

    // Handle bitch emojis -> bitch label (check both name and id for safety)
    if (this._isBitchEmoji(emojiName, emoji)) {
      console.log(`[Bitch Classifier] Poodle reaction detected on message ${messageId}`);
      this._handleBitchReaction(messageId, channelId, userId);
    }

    // Handle quality reactions (like knoxx)
    const quality = this._qualityFromEmoji(emoji);
    if (quality) {
      this._handleQualityReaction(messageId, channelId, userId, emoji, quality);
    }

    // Always ingest reaction as an event
    const event = this._reactionToEvent(reaction, channel, guildId, emoji, userId);
    if (event && !this._seen.has(event.id)) {
      this._seen.add(event.id);
      this._queue.push(event);
      this._persistQueue();
      if (this._queue.length >= CONFIG.maxBatchSize) void this._flush();
    }
  }

  _onReactionRemove(payload) {
    const reaction = payload;
    if (!reaction?.messageId || !reaction?.channelId) return;

    const channel = this._channelStore?.getChannel?.(reaction.channelId);
    const guildId = String(reaction.guildId ?? channel?.guild_id ?? channel?.getGuildId?.() ?? "");
    if (!CONFIG.guildIds.has(guildId)) return;

    const emojiName = reaction.emoji?.name ?? "";
    const emojiId = reaction.emoji?.id ?? "";
    const emoji = emojiName || emojiId;
    const userId = String(reaction.userId ?? "");
    const messageId = String(reaction.messageId ?? "");
    const channelId = String(reaction.channelId ?? "");

    // If bitch emoji removed, decrement bitch count
    if (this._isBitchEmoji(emojiName, emoji)) {
      this._handleBitchReactionRemove(messageId, channelId, userId);
    }
  }

  // ── Bitch Classifier ──────────────────────────────────────────────────────

  _handleBitchReaction(messageId, channelId, reactorUserId) {
    const message = this._messageStore?.getMessage?.(channelId, messageId);
    if (!message) {
      console.warn(`[Bitch Classifier] Could not find message ${messageId} in channel ${channelId}`);
      return;
    }

    const authorId = String(message.author?.id ?? "");
    if (!authorId) return;

    // Track this message as labeled
    if (!this._bitchMessages.has(authorId)) {
      this._bitchMessages.set(authorId, new Set());
    }
    this._bitchMessages.get(authorId).add(messageId);
    this._labeledMessages.add(messageId);

    // Increment bitch count
    const currentCount = this._bitchCounts.get(authorId) ?? 0;
    const newCount = currentCount + 1;
    this._bitchCounts.set(authorId, newCount);

    console.log(`[Bitch Classifier] User ${authorId} now has ${newCount} bitch label(s)`);

    // Send message to bitch tracker
    this._sendToBitchTracker(message, this._channelStore?.getChannel?.(channelId), null, "poodle-labeled");

    // If threshold reached, tag user as bitch
    if (newCount >= CONFIG.bitchThreshold && currentCount < CONFIG.bitchThreshold) {
      this._tagUserAsBitch(authorId, message);
    }

    // Queue for semantic similarity scan once embedded
    this._pendingSemanticQueries.add(messageId);

    // Also immediately try semantic search on the message content
    if (message.content) {
      setTimeout(async () => {
        const similar = await this._querySemanticSimilar(message.content, 5);
        if (similar.length > 0) {
          this._sendSimilarMessagesToWatch(authorId, message, similar);
        }
      }, 100);
    }

    this._persistBitchState();
  }

  _queueBitchWatchSemanticSearch(authorId, message) {
    setTimeout(async () => {
      const similar = await this._querySemanticSimilar(message.content, 8);
      if (similar.length > 0) this._sendSimilarMessagesToWatch(authorId, message, similar);
    }, 5000);
  }

  _handleBitchReactionRemove(messageId, channelId, reactorUserId) {
    const message = this._messageStore?.getMessage?.(channelId, messageId);
    if (!message) return;

    const authorId = String(message.author?.id ?? "");
    if (!authorId || !this._labeledMessages.has(messageId)) return;

    // Remove from tracking
    this._labeledMessages.delete(messageId);
    if (this._bitchMessages.has(authorId)) {
      this._bitchMessages.get(authorId).delete(messageId);
    }

    // Decrement count
    const currentCount = this._bitchCounts.get(authorId) ?? 0;
    if (currentCount > 0) {
      const newCount = currentCount - 1;
      this._bitchCounts.set(authorId, newCount);
      console.log(`[Bitch Classifier] User ${authorId} now has ${newCount} bitch label(s) (removed)`);
    }

    this._persistBitchState();
  }

  _tagUserAsBitch(userId, triggeringMessage) {
    console.log(`[Bitch Classifier] Tagging user ${userId} as BITCH`);

    // Send notification to bitch watch channel
    this._sendToBitchWatch(userId, triggeringMessage);

    // Add user note via Discord API (if available through modules)
    this._addUserNote(userId, "bitch");

    BdApi.UI?.showToast?.(`User tagged as bitch: ${userId}`, { type: "warning", timeout: 5000 });
  }

  _sendToBitchTracker(message, channel, guildId, reason) {
    const trackerChannelId = CONFIG.bitchTrackerChannelId;
    console.log(`[Bitch Classifier] _sendToBitchTracker called. trackerChannelId=${trackerChannelId}, msgChannelId=${message.channel_id}, reason=${reason}`);
    if (!trackerChannelId) {
      console.warn("[Bitch Classifier] No tracker channel configured");
      return;
    }
    // Ensure string comparison since channel IDs can be numbers or strings
    if (String(message.channel_id ?? "") === String(trackerChannelId)) {
      console.log("[Bitch Classifier] Message is already in tracker channel, skipping");
      return;
    }

    const content = message.content ?? "";
    const author = message.author;
    const authorName = this._sanitizeMentions(author?.username ?? author?.globalName ?? "Unknown");
    const authorId = String(author?.id ?? "");
    const guild = guildId === "@me" ? null : this._guildStore?.getGuild?.(guildId);
    const guildName = guild?.name ?? channel?.guild?.name ?? channel?.guildName ?? "unknown server";
    const messageLink = `https://discord.com/channels/${guildId ?? "@me"}/${message.channel_id}/${message.id}`;
    const messageTs = this._discordTimestamp(message.timestamp ?? message.timestamp?._i);
    const detectedTs = this._discordTimestamp(Date.now());

    const trackerMessage = [
      `**[${reason}]** Bitch activity detected`,
      `**Author:** ${authorName} (${authorId})`,
      `**Server:** ${guildName} (${guildId ?? "@me"})`,
      `**Channel:** #${channel?.name ?? "unknown"}`,
      `**Message timestamp:** ${messageTs}`,
      `**Detected:** ${detectedTs}`,
      `**Message:** ${content || "(no text content)"}`,
      `**Link:** ${messageLink}`,
    ].join("\n");

    console.log(`[Bitch Classifier] Sending tracker message for user ${authorId}`);
    this._sendDiscordMessage(trackerChannelId, trackerMessage);
  }

  _sendToBitchWatch(userId, triggeringMessage) {
    const watchChannelId = CONFIG.bitchWatchChannelId;
    if (!watchChannelId) return;

    const author = triggeringMessage.author;
    const authorName = this._sanitizeMentions(author?.username ?? author?.globalName ?? "Unknown");
    const count = this._bitchCounts.get(userId) ?? 0;
    const messageTs = this._discordTimestamp(triggeringMessage.timestamp ?? triggeringMessage.timestamp?._i);
    const detectedTs = this._discordTimestamp(Date.now());

    const watchMessage = [
      `🐩 **Bitch Alert**`,
      `User **${authorName}** (${userId}) has been tagged as a bitch.`,
      `Total poodle labels: ${count}`,
      `Message timestamp: ${messageTs}`,
      `Detected: ${detectedTs}`,
      `Triggering message: ${this._sanitizeMentions(triggeringMessage.content?.substring(0, 200) ?? "")}`,
      `This user will be monitored for similar behavior patterns.`,
    ].join("\n");

    this._sendDiscordMessage(watchChannelId, watchMessage);
  }

  _sendToModerationTracker(message, channel, guildId, hits) {
    const trackerChannelId = CONFIG.bitchTrackerChannelId;
    if (!trackerChannelId) return;

    const content = message.content ?? "";
    const author = message.author;
    const authorName = this._sanitizeMentions(author?.username ?? author?.globalName ?? "Unknown");
    const authorId = String(author?.id ?? "");
    const guild = guildId === "@me" ? null : this._guildStore?.getGuild?.(guildId);
    const guildName = guild?.name ?? channel?.guild?.name ?? channel?.guildName ?? "unknown server";
    const messageLink = `https://discord.com/channels/${guildId ?? "@me"}/${message.channel_id}/${message.id}`;
    const messageTs = this._discordTimestamp(message.timestamp ?? message.timestamp?._i);
    const detectedTs = this._discordTimestamp(Date.now());

    const trackerMessage = [
      `**[moderation-watch]** Watchlist term detected`,
      `**Author:** ${authorName} (${authorId})`,
      `**Server:** ${guildName} (${guildId ?? "@me"})`,
      `**Channel:** #${channel?.name ?? "unknown"}`,
      `**Message timestamp:** ${messageTs}`,
      `**Detected:** ${detectedTs}`,
      `**Matched watch terms:** ${hits.join(", ")}`,
      `**Message:** ${this._sanitizeMentions(content || "(no text content)")}`,
      `**Link:** ${messageLink}`,
    ].join("\n");

    this._sendDiscordMessage(trackerChannelId, trackerMessage);
  }

  async _sendDiscordMessage(channelId, content) {
    console.log(`[Bitch Classifier] Attempting to send message to ${channelId}`);
    const chunks = this._discordMessageChunks(content);
    const botOnly = this._shouldUseBotForChannel(channelId);

    for (const chunk of chunks) {
      const body = { content: chunk, nonce: this._nonce(), tts: false, allowed_mentions: { parse: [] } };

      if (botOnly) {
        if (await this._sendViaBotToken(channelId, body, "Bitch Classifier")) continue;
        console.error("[Bitch Classifier] Failed to send protected-channel Discord message via bot token; not falling back to user auth");
        return;
      }

      if (await this._sendViaDiscordHttp(channelId, body, "Bitch Classifier")) continue;
      if (await this._sendViaDiscordFetch(channelId, body, "Bitch Classifier")) continue;
      if (await this._sendViaMessageActions(channelId, body, "Bitch Classifier")) continue;

      console.error("[Bitch Classifier] Failed to send Discord message chunk: no send path succeeded");
      return;
    }
  }

  async _sendViaDiscordHttp(channelId, body, label) {
    const Http = BdApi.findModuleByProps?.("get", "post", "put", "del")
      || BdApi.Webpack.getModule(m => m?.post && m?.get && typeof m.post === "function", { searchExports: true })
      || BdApi.Webpack.getModule(m => m?.HTTP?.post && typeof m.HTTP.post === "function", { searchExports: true })?.HTTP;
    const Endpoints = BdApi.findModuleByProps?.("Endpoints")?.Endpoints
      || BdApi.Webpack.getModule(m => m?.Endpoints?.MESSAGES, { searchExports: true })?.Endpoints;
    const url = Endpoints?.MESSAGES ? Endpoints.MESSAGES(channelId) : `/channels/${channelId}/messages`;
    if (!Http?.post) return false;
    try {
      console.log(`[${label}] Sending via Discord HTTP module`);
      await Http.post({ url, body });
      return true;
    } catch (err) {
      console.warn(`[${label}] HTTP module send failed`, err);
      return false;
    }
  }

  async _sendViaDiscordFetch(channelId, body, label) {
    const authModule = BdApi.findModuleByProps?.("getToken")
      || BdApi.Webpack.getModule(m => m?.getToken && typeof m.getToken === "function", { searchExports: true });
    const authToken = authModule?.getToken?.();
    if (!authToken) {
      console.warn(`[${label}] No auth token available for fetch fallback`);
      return false;
    }
    try {
      console.log(`[${label}] Sending via fetch fallback`);
      const fetchImpl = BdApi.Net?.fetch ?? fetch;
      const res = await fetchImpl(`https://discord.com/api/v9/channels/${channelId}/messages`, {
        method: "POST",
        headers: { Authorization: authToken, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
      return true;
    } catch (err) {
      console.warn(`[${label}] fetch send failed`, err);
      return false;
    }
  }

  async _sendViaMessageActions(channelId, body, label) {
    const MessageActions = BdApi.findModuleByProps?.("sendMessage", "editMessage", "deleteMessage")
      || BdApi.Webpack.getModule(m => m?.sendMessage && typeof m.sendMessage === "function", { searchExports: true });
    if (!MessageActions?.sendMessage) return false;
    const attempts = [
      () => MessageActions.sendMessage(channelId, body, undefined, { nonce: body.nonce }),
      () => MessageActions.sendMessage(channelId, body, undefined, undefined, { nonce: body.nonce }),
      () => MessageActions.sendMessage(channelId, body, { nonce: body.nonce }),
    ];
    for (const attempt of attempts) {
      try {
        console.log(`[${label}] Sending via MessageActions fallback`);
        await attempt();
        return true;
      } catch (err) {
        console.warn(`[${label}] MessageActions attempt failed`, err);
      }
    }
    return false;
  }

  async _sendViaBotToken(channelId, body, label) {
    const botConfig = this._botConfig();
    if (!botConfig?.token) {
      console.warn(`[${label}] bot.json missing or invalid; protected-channel send skipped`);
      return false;
    }

    const fetchImpl = BdApi.Net?.fetch ?? fetch;
    const url = `https://discord.com/api/v10/channels/${channelId}/messages`;
    const headers = {
      Authorization: this._botAuthorizationHeader(botConfig.token),
      "Content-Type": "application/json",
    };

    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        const res = await fetchImpl(url, {
          method: "POST",
          headers,
          body: JSON.stringify(body),
        });

        if (res.ok) {
          const suffix = botConfig.username ? ` as ${botConfig.username}` : "";
          console.log(`[${label}] Sent via bot token${suffix}`);
          return true;
        }

        const responseText = typeof res.text === "function" ? await res.text().catch(() => "") : "";
        const retryAfterMs = this._retryAfterMs(res, responseText);
        if (res.status === 429 && retryAfterMs > 0 && attempt < 3) {
          console.warn(`[${label}] Bot send rate limited; retrying in ${retryAfterMs}ms`);
          await this._sleep(retryAfterMs);
          continue;
        }
        if (res.status >= 500 && attempt < 3) {
          const delayMs = 500 * attempt;
          console.warn(`[${label}] Bot send HTTP ${res.status}; retrying in ${delayMs}ms`);
          await this._sleep(delayMs);
          continue;
        }

        console.warn(`[${label}] Bot send failed HTTP ${res.status}: ${this._redactToken(responseText, botConfig.token).slice(0, 500)}`);
        return false;
      } catch (err) {
        if (attempt < 3) {
          const delayMs = 500 * attempt;
          console.warn(`[${label}] Bot send attempt failed; retrying in ${delayMs}ms`, err);
          await this._sleep(delayMs);
          continue;
        }
        console.warn(`[${label}] Bot send failed`, err);
        return false;
      }
    }

    return false;
  }

  _shouldUseBotForChannel(channelId) {
    return [CONFIG.bitchTrackerChannelId, CONFIG.bitchWatchChannelId]
      .some(targetId => String(targetId) === String(channelId));
  }

  _discordMessageChunks(content) {
    const text = String(content ?? "");
    if (text.length <= 1900) return [text || "(empty message)"];

    const chunks = [];
    let rest = text;
    while (rest.length > 1900) {
      let splitAt = rest.lastIndexOf("\n", 1900);
      if (splitAt < 500) splitAt = 1900;
      chunks.push(rest.slice(0, splitAt));
      rest = rest.slice(splitAt).replace(/^\n+/, "");
    }
    if (rest) chunks.push(rest);

    return chunks.map((chunk, index) => chunks.length === 1 ? chunk : `(${index + 1}/${chunks.length})\n${chunk}`);
  }

  _botConfigStatus() {
    const botConfig = this._botConfig();
    if (!botConfig?.token) return "bot.json: missing or invalid token; tracker/watch sends are blocked instead of using user auth";

    const identity = [botConfig.username, botConfig.botUserId ? `(${botConfig.botUserId})` : ""]
      .filter(Boolean)
      .join(" ") || "configured bot";
    const alias = botConfig.botServerAliasName ? ` for ${botConfig.botServerAliasName}` : "";
    return `bot.json: ${identity}${alias}; tracker/watch sends use Bot REST only`;
  }

  _botUserId() {
    return this._botConfig()?.botUserId ?? "";
  }

  _botConfig() {
    try {
      const fs = require("fs");
      const file = this._pluginFilePath("bot.json");
      if (!file || !fs.existsSync(file)) return null;

      const raw = fs.readFileSync(file, "utf8");
      const parsed = JSON.parse(raw);
      const token = String(parsed.token ?? this._jsonFieldString(raw, "token", "")).trim();
      if (!token || token === "<bot-token>") return null;

      return {
        username: String(parsed.username ?? "").trim(),
        token,
        appId: this._jsonFieldString(raw, "appId", parsed.appId),
        botUserId: this._jsonFieldString(raw, "botUserId", parsed.botUserId),
        botServerAliasName: String(parsed.botServerAliasName ?? "").trim(),
      };
    } catch (err) {
      console.warn("[Bitch Classifier] Failed to read bot.json", err);
      return null;
    }
  }

  _jsonFieldString(raw, key, parsedValue) {
    const match = String(raw ?? "").match(new RegExp(`"${this._escapeRegExp(key)}"\\s*:\\s*("(?:\\\\.|[^"\\\\])*"|[0-9]+)`));
    if (match) {
      const value = match[1];
      if (value.startsWith('"')) {
        try {
          return String(JSON.parse(value));
        } catch (_err) {
          return value.slice(1, -1);
        }
      }
      return value;
    }
    return parsedValue == null ? "" : String(parsedValue);
  }

  _pluginFilePath(filename) {
    try {
      const fs = require("fs");
      const candidates = this._pluginFileCandidates(filename);
      return candidates.find(file => fs.existsSync(file)) ?? candidates[0] ?? "";
    } catch (_err) {
      return "";
    }
  }

  _pluginFileCandidates(filename) {
    const path = require("path");
    const candidates = [];

    if (typeof __dirname === "string" && __dirname) candidates.push(path.join(__dirname, filename));
    if (typeof BdApi !== "undefined" && BdApi?.Plugins?.folder) candidates.push(path.join(BdApi.Plugins.folder, filename));

    const home = globalThis.process?.env?.HOME ?? "/home/err";
    candidates.push(
      path.join(home, "snap/discord/current/.config/BetterDiscord/plugins", filename),
      path.join(home, ".config/BetterDiscord/plugins", filename),
    );

    return [...new Set(candidates)];
  }

  _botAuthorizationHeader(token) {
    const value = String(token ?? "").trim();
    return /^Bot\s+/i.test(value) ? value : `Bot ${value}`;
  }

  _retryAfterMs(res, responseText) {
    const headerValue = res.headers?.get?.("retry-after");
    if (headerValue) {
      const seconds = Number(headerValue);
      if (Number.isFinite(seconds) && seconds > 0) return Math.min(10000, Math.ceil(seconds * 1000));
    }

    try {
      const retryAfter = Number(JSON.parse(responseText).retry_after);
      if (Number.isFinite(retryAfter) && retryAfter > 0) return Math.min(10000, Math.ceil(retryAfter * 1000));
    } catch (_err) {
      // ignore malformed non-JSON error bodies
    }
    return 0;
  }

  _redactToken(text, token) {
    const value = String(text ?? "");
    const secret = String(token ?? "");
    return secret ? value.split(secret).join("<bot-token>") : value;
  }

  _sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  _nonce() {
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }

  _addUserNote(userId, note) {
    try {
      const UserProfileActions = BdApi.Webpack.getModule(m => m?.addNote || m?.updateNote, { searchExports: true });
      if (UserProfileActions?.addNote) {
        UserProfileActions.addNote(userId, note);
        console.log(`[Bitch Classifier] Added note "${note}" to user ${userId}`);
      } else {
        console.warn("[Bitch Classifier] Could not find addNote action");
      }
    } catch (err) {
      console.error("[Bitch Classifier] Failed to add user note:", err);
    }
  }

  // ── Semantic Search ───────────────────────────────────────────────────────

  async _querySemanticSimilar(text, k = 10) {
    const endpoint = this._endpoint().replace("/v1/events", "/v1/graph/similar");
    const apiKey = this._apiKey();
    if (!endpoint || !apiKey) return [];

    try {
      const res = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "authorization": this._authorizationHeader(apiKey),
        },
        body: JSON.stringify({
          q: this._semanticQueryText(text),
          k,
          where: { source: "betterdiscord-openplanner" },
        }),
      });

      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);

      const data = await res.json();
      if (!data?.hits) return [];

      return data.hits.filter(hit => {
        const similarity = 1 - (hit.distance ?? 0);
        return similarity >= 0.75; // only high-similarity hits
      });
    } catch (err) {
      console.warn("[Semantic Search] Query failed:", err);
      return [];
    }
  }

  _semanticQueryText(text) {
    const instruction = this._setting("semanticQueryInstruction", CONFIG.defaultSemanticQueryInstruction).trim();
    const message = String(text ?? "").trim();
    return instruction ? `${instruction}\n\nDiscord message:\n${message}` : message;
  }

  async _runSemanticScan() {
    if (this._pendingSemanticQueries.size === 0) return;

    const apiKey = this._apiKey();
    const baseUrl = this._endpoint().replace("/v1/events", "");
    if (!apiKey || !baseUrl) return;

    // Check which messages now have embeddings
    const toCheck = Array.from(this._pendingSemanticQueries).slice(0, 10);
    const eventIds = toCheck.map(id => `discord:discord.message:${id}`);

    try {
      const res = await fetch(`${baseUrl}/v1/graph/node-embeddings/query`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "authorization": this._authorizationHeader(apiKey),
        },
        body: JSON.stringify({ eventIds }),
      });

      if (!res.ok) return;

      const data = await res.json();
      const embeddedIds = new Set((data.vectors || []).map(v => v.sourceEventId || v.id));

      for (const messageId of toCheck) {
        const eventId = `discord:discord.message:${messageId}`;
        if (embeddedIds.has(eventId)) {
          this._pendingSemanticQueries.delete(messageId);
          // Find the original message and run semantic search
          for (const [userId, messageSet] of this._bitchMessages) {
            if (messageSet.has(messageId)) {
              const channelId = this._findChannelIdForMessage(messageId);
              if (channelId) {
                const message = this._messageStore?.getMessage?.(channelId, messageId);
                if (message?.content) {
                  const similar = await this._querySemanticSimilar(message.content, 5);
                  if (similar.length > 0) {
                    this._sendSimilarMessagesToWatch(userId, message, similar);
                  }
                }
              }
              break;
            }
          }
        }
      }
    } catch (err) {
      console.warn("[Semantic Scan] Failed:", err);
    }
  }

  _findChannelIdForMessage(messageId) {
    // Search through all channels we know about
    const channels = this._channelStore?.getMutablePrivateChannels?.() || [];
    for (const channel of Object.values(channels)) {
      if (this._messageStore?.getMessage?.(channel.id, messageId)) {
        return channel.id;
      }
    }
    return null;
  }

  _sendSimilarMessagesToWatch(userId, sourceMessage, similarHits) {
    const watchChannelId = CONFIG.bitchWatchChannelId;
    if (!watchChannelId) return;

    const author = sourceMessage.author;
    const authorName = this._sanitizeMentions(author?.username ?? author?.globalName ?? "Unknown");

    const similarLinks = similarHits.map((hit, index) => {
      const meta = hit.metadata || {};
      const similarity = (1 - (hit.distance ?? 0)).toFixed(3);
      const text = this._sanitizeMentions((hit.document || meta.text || "").substring(0, 150));
      const source = this._sanitizeMentions(meta.source || "unknown");
      return `${index + 1}. [sim:${similarity}] ${text}${text.length >= 150 ? "..." : ""} (source: ${source})`;
    });

    const watchMessage = [
      `🔍 **Semantic Similarity Alert**`,
      `Bitch message from **${authorName}** (${userId}):`,
      `\"> ${sourceMessage.content?.substring(0, 200) ?? ""}\"`,
      ``,
      `**Top ${similarHits.length} similar messages:**`,
      ...similarLinks,
    ].join("\n");

    this._sendDiscordMessage(watchChannelId, watchMessage);
  }

  // ── Quality Reactions (Knoxx-style) ───────────────────────────────────────

  _qualityFromEmoji(emoji) {
    switch (emoji) {
      case "✅":
      case "☑️":
      case "✔️":
      case "✔":
        return "good";
      case "❌":
      case "✖️":
      case "✖":
      case "❎":
        return "bad";
      default:
        return null;
    }
  }

  _handleQualityReaction(messageId, channelId, userId, emoji, quality) {
    console.log(`[Quality Label] Message ${messageId} labeled as ${quality} by ${userId}`);

    const message = this._messageStore?.getMessage?.(channelId, messageId);
    if (!message) return;

    // Create a weak-reaction quality event
    const channel = this._channelStore?.getChannel?.(channelId);
    const guildId = String(message.guild_id ?? channel?.guild_id ?? channel?.getGuildId?.() ?? "");

    const event = {
      schema: "openplanner.event.v1",
      schema_version: 1,
      id: `discord:quality:${channelId}:${messageId}:${emoji}`,
      ts: new Date().toISOString(),
      source: "betterdiscord-openplanner",
      kind: "discord.reaction",
      source_ref: {
        project: this._setting("project", CONFIG.defaultProject),
        session: channelId,
        message: messageId,
      },
      text: `Quality label: ${quality}`,
      meta: {
        author: userId,
        author_id: userId,
        tags: ["discord", "reaction", "quality-label"],
      },
      extra: {
        guild_id: guildId,
        channel_id: channelId,
        message_id: messageId,
        reaction_emoji: emoji,
        reaction_user_id: userId,
        quality,
        openplanner_labels: {
          claim_system: "weak-reaction-v1",
          reaction_emojis: [emoji],
          labels: [`quality:${quality}`],
          quality,
          explicit_meaning: quality === "good" ? "good output" : "bad output",
          updated_at: new Date().toISOString(),
        },
      },
    };

    if (!this._seen.has(event.id)) {
      this._seen.add(event.id);
      this._queue.push(event);
      this._persistQueue();
      if (this._queue.length >= CONFIG.maxBatchSize) void this._flush();
    }
  }

  // ── Event Builders ────────────────────────────────────────────────────────

  _messageToEvent(message, channel, guildId) {
    const guild = this._guildStore?.getGuild?.(guildId);
    const author = message.author ?? this._userStore?.getUser?.(message.author?.id ?? message.author_id);
    const authorId = String(author?.id ?? message.author_id ?? "unknown");
    const content = typeof message.content === "string" ? message.content : "";
    const attachmentList = [...(message.attachments?.values?.() ?? message.attachments ?? [])].map(a => ({
      id: String(a.id ?? ""),
      filename: a.filename ?? a.name ?? null,
      content_type: a.content_type ?? a.contentType ?? null,
      size: a.size ?? null,
      url: a.url ?? null,
      proxy_url: a.proxy_url ?? a.proxyURL ?? null,
      width: a.width ?? null,
      height: a.height ?? null,
    }));

    const embedList = [...(message.embeds ?? [])].map(e => ({
      type: e.type ?? null,
      title: e.title ?? null,
      description: e.description ?? null,
      url: e.url ?? null,
      provider: e.provider?.name ?? null,
    }));

    const ts = new Date(message.timestamp ?? message.timestamp?._i ?? Date.now()).toISOString();
    const moderationHits = this._moderationHits(content);
    const labels = [];
    if (CONFIG.knownBitchUserIds.has(authorId)) labels.push("bitch-watch:known-user", `bitch-watch:user:${authorId}`);
    if (moderationHits.length > 0) labels.push("moderation-watch:term", ...moderationHits.map(hit => `moderation-watch:${hit}`));

    const event = {
      schema: "openplanner.event.v1",
      schema_version: 1,
      id: `discord:${guildId}:${message.channel_id}:${message.id}`,
      ts,
      source: "betterdiscord-openplanner",
      kind: "discord.message",
      source_ref: {
        project: this._setting("project", CONFIG.defaultProject),
        session: guildId,
        message: String(message.id),
      },
      text: content || attachmentList.map(a => a.url).filter(Boolean).join("\n") || embedList.map(e => e.url).filter(Boolean).join("\n"),
      meta: {
        author: author?.username ?? author?.globalName ?? authorId,
        author_id: authorId,
        author_username: author?.username ?? null,
        author_global_name: author?.globalName ?? null,
        bot: Boolean(author?.bot),
        tags: ["discord", "message"],
      },
      extra: {
        guild_id: guildId,
        guild_name: guild?.name ?? null,
        channel_id: String(message.channel_id),
        channel_name: channel?.name ?? null,
        message_id: String(message.id),
        nonce: message.nonce ?? null,
        type: message.type ?? null,
        flags: message.flags ?? null,
        pinned: Boolean(message.pinned),
        tts: Boolean(message.tts),
        mention_everyone: Boolean(message.mention_everyone),
        mentions: this._ids(message.mentions),
        mention_roles: this._ids(message.mention_roles),
        attachments: attachmentList,
        embeds: embedList,
        edited_timestamp: message.edited_timestamp ?? null,
      },
    };

    if (labels.length > 0) {
      event.extra.openplanner_labels = {
        claim_system: "discord-moderation-watch-v1",
        labels: [...new Set(labels)],
        updated_at: new Date().toISOString(),
      };
    }

    // If user is a known bitch, add bitch label
    if (CONFIG.knownBitchUserIds.has(authorId)) {
      event.meta.tags.push("known-bitch");
      event.meta.tags.push("bitch-watch");
      event.extra.is_known_bitch = true;
    }

    if (moderationHits.length > 0) {
      event.meta.tags.push("moderation-watch");
      event.extra.moderation_watch_hits = moderationHits;
    }

    return event;
  }

  _reactionToEvent(reaction, channel, guildId, emoji, userId) {
    const messageId = String(reaction.messageId ?? "");
    const channelId = String(reaction.channelId ?? "");
    if (!messageId || !channelId) return null;

    const ts = new Date().toISOString();
    const reactionLabel = this._reactionLabel(emoji);

    const event = {
      schema: "openplanner.event.v1",
      schema_version: 1,
      id: `discord:reaction:${guildId}:${channelId}:${messageId}:${emoji}:${userId}`,
      ts,
      source: "betterdiscord-openplanner",
      kind: "discord.reaction",
      source_ref: {
        project: this._setting("project", CONFIG.defaultProject),
        session: guildId,
        message: messageId,
      },
      text: `Reaction: ${emoji}`,
      meta: {
        author: userId,
        author_id: userId,
        tags: ["discord", "reaction"],
      },
      extra: {
        guild_id: guildId,
        channel_id: channelId,
        message_id: messageId,
        reaction_emoji: emoji,
        reaction_user_id: userId,
        openplanner_labels: {
          claim_system: "discord-reaction-v1",
          reaction_emojis: [emoji],
          labels: [`reaction:${reactionLabel}`],
          updated_at: new Date().toISOString(),
        },
      },
    };

    event.meta.tags.push("reaction-label");

    if (this._qualityFromEmoji(emoji)) {
      const quality = this._qualityFromEmoji(emoji);
      event.meta.tags.push("quality-label");
      event.extra.openplanner_labels.labels.push(`quality:${quality}`);
      event.extra.openplanner_labels.claim_system = "discord-quality-v1";
      event.extra.openplanner_labels.quality = quality;
      event.extra.quality = quality;
    }

    if (this._isBitchEmoji(emoji)) {
      event.meta.tags.push("bitch-label", "poodle-label");
      event.extra.openplanner_labels.claim_system = "discord-moderation-watch-v1";
      event.extra.openplanner_labels.labels.push("bitch:poodle", "bitch-watch:poodle-label");
    }

    return event;
  }

  // ── Persistence ───────────────────────────────────────────────────────────

  _persistBitchState() {
    const state = {
      counts: Array.from(this._bitchCounts.entries()),
      messages: Array.from(this._bitchMessages.entries()).map(([k, v]) => [k, Array.from(v)]),
      labeled: Array.from(this._labeledMessages),
      pendingSemantic: Array.from(this._pendingSemanticQueries),
    };
    BdApi.Data.save("OpenPlannerEventIngest", "bitchState", state);
  }

  _loadBitchState() {
    const state = BdApi.Data.load("OpenPlannerEventIngest", "bitchState");
    if (state) {
      if (state.counts) this._bitchCounts = new Map(state.counts);
      if (state.messages) {
        this._bitchMessages = new Map(state.messages.map(([k, v]) => [k, new Set(v)]));
      }
      if (state.labeled) this._labeledMessages = new Set(state.labeled);
      if (state.pendingSemantic) this._pendingSemanticQueries = new Set(state.pendingSemantic);
    }
  }

  // ── OpenPlanner Flush ─────────────────────────────────────────────────────

  async _flush() {
    if (!this._queue.length) return;

    const endpoint = this._endpoint();
    const apiKey = this._apiKey();
    if (!endpoint || !apiKey) return;

    const batch = this._queue.slice(0, CONFIG.maxBatchSize);
    try {
      const res = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "authorization": this._authorizationHeader(apiKey),
        },
        body: JSON.stringify({ events: batch }),
      });

      if (!res.ok) throw new Error(`${res.status} ${res.statusText}: ${await res.text().catch(() => "")}`);

      this._queue.splice(0, batch.length);
      this._persistQueue();
      console.log(`[OpenPlanner Event Ingest] Sent ${batch.length} event(s)`);
    } catch (err) {
      console.warn("[OpenPlanner Event Ingest] Flush failed; will retry", err);
    }
  }

  // ── Utilities ─────────────────────────────────────────────────────────────

  _ids(value) {
    if (!value) return [];
    const arr = Array.isArray(value) ? value : [...(value.values?.() ?? [])];
    return arr.map(v => String(v?.id ?? v)).filter(Boolean);
  }

  _reactionLabel(emoji) {
    const value = String(emoji ?? "").trim();
    if (!value) return "unknown";

    const customMatch = value.match(/^<a?:([^:>]+):([0-9]+)>$/);
    if (customMatch) return `custom:${customMatch[1]}:${customMatch[2]}`;
    if (/^[0-9]+$/.test(value)) return `custom:${value}`;

    return `unicode:${[...value].map(ch => ch.codePointAt(0).toString(16)).join("-")}`;
  }

  _isBitchEmoji(emojiName, emoji = emojiName) {
    return [CONFIG.poodleEmoji, CONFIG.clownEmoji].some(value => emojiName === value || emoji === value);
  }

  _sanitizeMentions(text) {
    return String(text ?? "")
      .replace(/@everyone/g, "@\u200beveryone")
      .replace(/@here/g, "@\u200bhere")
      .replace(/<@!?([0-9]+)>/g, "<@\u200b$1>")
      .replace(/<@&([0-9]+)>/g, "<@&\u200b$1>")
      .replace(/<#([0-9]+)>/g, "<#\u200b$1>");
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
      const file = this._pluginFilePath("OpenPlannerModerationWatchlist.json");
      if (!file || !fs.existsSync(file)) return {};
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch (err) {
      console.warn("[OpenPlanner Event Ingest] Failed to read moderation watchlist config", err);
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

  _endpoint() {
    const explicitEndpoint = this._setting("endpoint", "").trim();
    if (explicitEndpoint) return explicitEndpoint;

    const baseUrl = (this._env("OPENPLANNER_BASE_URL") || CONFIG.defaultBaseUrl).replace(/\/+$/, "");
    return `${baseUrl}/v1/events`;
  }

  _apiKey() {
    return (this._setting("apiKey", "") || this._env("OPENPLANNER_API_KEY") || "").trim();
  }

  _authorizationHeader(apiKeyOrHeader) {
    const value = String(apiKeyOrHeader ?? "").trim();
    if (/^Bearer\s+/i.test(value)) return value;
    return `Bearer ${value}`;
  }

  _discordTimestamp(value) {
    const date = value ? new Date(value) : new Date();
    const ms = Number.isNaN(date.getTime()) ? Date.now() : date.getTime();
    return `<t:${Math.floor(ms / 1000)}:F>`;
  }

  _env(name) {
    const fromProcess = globalThis.process?.env?.[name];
    if (fromProcess) return fromProcess;

    for (const file of [
      "/home/err/devel/services/openplanner/.env",
      "/home/err/devel/orgs/open-hax/openplanner/.env",
    ]) {
      const value = this._envFileValue(file, name);
      if (value) return value;
    }

    return "";
  }

  _envFileValue(file, name) {
    try {
      const fs = require("fs");
      if (!fs.existsSync(file)) return "";
      const prefix = `${name}=`;
      const line = fs.readFileSync(file, "utf8")
        .split(/\r?\n/)
        .find(raw => raw.trim().startsWith(prefix));
      if (!line) return "";
      return line.trim().slice(prefix.length).replace(/^['\"]|['\"]$/g, "");
    } catch (_err) {
      return "";
    }
  }

  _setting(key, fallback) {
    return BdApi.Data.load("OpenPlannerEventIngest", key) ?? fallback;
  }

  _saveSetting(key, value) {
    BdApi.Data.save("OpenPlannerEventIngest", key, value);
  }

  _field(labelText, key, value, placeholder, password = false) {
    const wrap = document.createElement("label");
    wrap.style.cssText = "display:flex;flex-direction:column;gap:6px;font-weight:600;";
    const label = document.createElement("span");
    label.textContent = labelText;
    const input = document.createElement("input");
    input.type = password ? "password" : "text";
    input.value = value;
    input.placeholder = placeholder;
    input.style.cssText = "padding:8px 10px;border-radius:6px;border:1px solid var(--background-modifier-border);background:var(--background-secondary);color:var(--text-normal);";
    input.addEventListener("change", () => this._saveSetting(key, input.value));
    wrap.append(label, input);
    return wrap;
  }

  _note(text) {
    const el = document.createElement("div");
    el.style.cssText = "font-size:12px;color:var(--text-muted);line-height:1.4;";
    el.textContent = text;
    return el;
  }

  _persistQueue() {
    const persisted = this._queue.slice(-CONFIG.maxPersistedEvents);
    BdApi.Data.save("OpenPlannerEventIngest", "queue", persisted);
  }

  _loadPersistedQueue() {
    const persisted = BdApi.Data.load("OpenPlannerEventIngest", "queue");
    if (Array.isArray(persisted)) {
      this._queue = persisted.slice(-CONFIG.maxPersistedEvents);
      for (const ev of this._queue) this._seen.add(ev.id);
    }
  }
};
