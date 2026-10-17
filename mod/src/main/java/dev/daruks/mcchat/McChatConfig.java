package dev.daruks.mcchat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** config/mcchat.json. Re-read every time a server is joined. */
public final class McChatConfig {
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

	private McChatConfig normalized() {
		if (serverUrl == null) serverUrl = "";
		if (token == null) token = "";
		if (channelOverride == null) channelOverride = "";
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
