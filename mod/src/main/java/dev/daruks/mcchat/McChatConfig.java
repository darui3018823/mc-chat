package dev.daruks.mcchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** config/mcchat.json. Re-read every time a server is joined. */
public final class McChatConfig {
	public static final int MIN_SCALE = 50;
	public static final int MAX_SCALE = 200;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** WebSocket endpoint of mcchat-server. */
	public String serverUrl = "wss://mcchat.daruks.com/ws";
	/** Token issued with `mcchat-server token add <name>`. */
	public String token = "";
	/** Mirror messages into the vanilla chat HUD. */
	public boolean showInChatHud = true;
	/**
	 * Use this channel for every world instead of the server address.
	 * Mainly for testing in singleplayer; leave empty normally.
	 */
	public String channelOverride = "";
	/** Convert romaji to Japanese before sending, like LunaChat. Toggled with /japanize. */
	public boolean japanize = true;
	/** Words replaced as is during romaji conversion, e.g. "maikura" -> "マイクラ". Edited with /dict. */
	public Map<String, String> dictionary = new LinkedHashMap<>();
	/** Font id for mcchat lines, e.g. "minecraft:uniform"; empty for the default font. Set with /font. */
	public String font = "";
	/** OS font file installed by /font system, e.g. "meiryo.ttc". */
	public String systemFont = "";
	/** Glyph size and oversampling for the OS font (resource pack ttf provider values). */
	public float systemFontSize = 11;
	public float systemFontOversample = 4;
	/** Text size of the mcchat screen log in percent, any font. Set with /font size. */
	public int textScale = 100;

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcchat.json");
	}

	/** Loads the config, writing defaults if the file does not exist. */
	public static McChatConfig load() {
		Path p = path();
		if (!Files.exists(p)) {
			McChatConfig def = new McChatConfig();
			try {
				Files.createDirectories(p.getParent());
				Files.writeString(p, GSON.toJson(def), StandardCharsets.UTF_8);
			} catch (IOException e) {
				McChatClient.LOG.warn("could not write default config {}", p, e);
			}
			return def;
		}
		try {
			McChatConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), McChatConfig.class);
			return c != null ? c.normalized() : new McChatConfig();
		} catch (IOException | JsonParseException e) {
			McChatClient.LOG.error("invalid config {}, using defaults", p, e);
			return new McChatConfig();
		}
	}

	/** Writes this config back to the file. */
	public void save() {
		Path p = path();
		try {
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			McChatClient.LOG.warn("could not write config {}", p, e);
		}
	}

	private McChatConfig normalized() {
		if (serverUrl == null) serverUrl = "";
		if (token == null) token = "";
		if (channelOverride == null) channelOverride = "";
		if (dictionary == null) dictionary = new LinkedHashMap<>();
		if (font == null) font = "";
		if (systemFont == null) systemFont = "";
		if (systemFontSize <= 0) systemFontSize = 11;
		if (systemFontOversample <= 0) systemFontOversample = 4;
		if (textScale < MIN_SCALE || textScale > MAX_SCALE) textScale = 100;
		font = font.trim();
		serverUrl = serverUrl.trim();
		token = token.trim();
		channelOverride = channelOverride.trim();
		return this;
	}

	/** Returns the endpoint URI, or null if serverUrl is not a ws/wss URL. */
	public URI uri() {
		try {
			URI u = URI.create(serverUrl);
			String s = u.getScheme();
			return ("ws".equalsIgnoreCase(s) || "wss".equalsIgnoreCase(s)) && u.getHost() != null ? u : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
