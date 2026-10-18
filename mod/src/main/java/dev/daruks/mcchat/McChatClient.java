package dev.daruks.mcchat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McChatClient implements ClientModInitializer {
	public static final String MOD_ID = "mcchat";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	/** Same as the server's default MaxRunes; longer messages are cut by the server. */
	private static final int MAX_LENGTH = 256;

	private static final ChatLog LOG_STATE = new ChatLog();
	private static ChatConnection connection;
	private static KeyMapping openKey;
	private static McChatConfig config = new McChatConfig();

	public static ChatLog log() {
		return LOG_STATE;
	}

	/**
	 * Handles a line typed by the player: a local command such as /japanize,
	 * or a message for the current channel ("//text" sends "/text").
	 * Returns false if it could not be sent, so the caller keeps the text.
	 */
	public static boolean submit(String text) {
		if (text.startsWith("/") && !text.startsWith("//")) {
			command(text.substring(1).trim());
			return true;
		}
		if (text.startsWith("//")) text = text.substring(1);
		if (connection == null || !connection.connected()) {
			notConnected();
			return false;
		}
		if (!config.japanize || !Japanizer.shouldConvert(text, config.dictionary)) {
			send(text, text);
			return true;
		}
		sendJapanized(text, text, false);
		return true;
	}

	private static void sendJapanized(String typed, String romaji, boolean force) {
		Japanizer.convert(romaji, force, config.dictionary)
				.thenAccept(jp -> Minecraft.getInstance().execute(() -> send(typed, japanized(romaji, jp))));
	}

	/** "ohayou (おはよう)" like LunaChat, or just the conversion if that would be too long. */
	private static String japanized(String romaji, String jp) {
		if (jp.equals(romaji)) return romaji;
		String both = romaji + " (" + jp + ")";
		return both.length() <= MAX_LENGTH ? both : jp;
	}

	private static void send(String typed, String text) {
		if (connection != null && connection.send(text)) {
			// The typed text, not the converted one, goes back into the input if rejected.
			LOG_STATE.sent(typed);
		} else {
			notConnected();
		}
	}

	private static void notConnected() {
		LOG_STATE.notice(Component.translatable("mcchat.error.not_connected"), ChatFormatting.YELLOW);
	}

	private static void command(String line) {
		String[] args = line.split("\\s+");
		switch (args[0].toLowerCase()) {
			case "jp" -> {
				// Forced conversion of the rest of the line, regardless of /japanize.
				String rest = line.substring(args[0].length()).trim();
				if (rest.isEmpty()) {
					LOG_STATE.notice(Component.translatable("mcchat.command.jp.usage"), ChatFormatting.YELLOW);
				} else if (connection == null || !connection.connected()) {
					notConnected();
				} else {
					sendJapanized("/" + line, rest, true);
				}
			}
			case "japanize" -> {
				if (args.length >= 2 && !args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off")) {
					LOG_STATE.notice(Component.translatable("mcchat.command.japanize.usage"), ChatFormatting.YELLOW);
					return;
				}
				boolean on = args.length < 2 ? !config.japanize : args[1].equalsIgnoreCase("on");
				config.japanize = on;
				config.save();
				LOG_STATE.notice(Component.translatable(on ? "mcchat.command.japanize.on" : "mcchat.command.japanize.off"),
						ChatFormatting.GREEN);
			}
			case "dict" -> dictCommand(line, args);
			case "font" -> fontCommand(args);
			default -> LOG_STATE.notice(Component.translatable("mcchat.command.unknown", args[0]), ChatFormatting.YELLOW);
		}
	}

	/** /dict add <word> <replacement...> | remove <word> | list */
	private static void dictCommand(String line, String[] args) {
		String sub = args.length >= 2 ? args[1].toLowerCase() : "";
		switch (sub) {
			case "add" -> {
				if (args.length < 4) {
					LOG_STATE.notice(Component.translatable("mcchat.command.dict.usage"), ChatFormatting.YELLOW);
					return;
				}
				String word = args[2].toLowerCase();
				// The replacement is the rest of the line after the word, spaces included.
				String value = line.split("\\s+", 4)[3].trim();
				config.dictionary.put(word, value);
				config.save();
				LOG_STATE.notice(Component.translatable("mcchat.command.dict.added", word, value), ChatFormatting.GREEN);
			}
			case "remove" -> {
				if (args.length < 3) {
					LOG_STATE.notice(Component.translatable("mcchat.command.dict.usage"), ChatFormatting.YELLOW);
					return;
				}
				String word = args[2].toLowerCase();
				if (config.dictionary.remove(word) == null) {
					LOG_STATE.notice(Component.translatable("mcchat.command.dict.missing", word), ChatFormatting.YELLOW);
					return;
				}
				config.save();
				LOG_STATE.notice(Component.translatable("mcchat.command.dict.removed", word), ChatFormatting.GREEN);
			}
			case "list" -> {
				if (config.dictionary.isEmpty()) {
					LOG_STATE.notice(Component.translatable("mcchat.command.dict.empty"), ChatFormatting.GRAY);
					return;
				}
				LOG_STATE.info(Component.translatable("mcchat.command.dict.header", config.dictionary.size())
						.withStyle(ChatFormatting.GREEN));
				config.dictionary.forEach((k, v) -> LOG_STATE.info(Component.literal(k + " → " + v)));
			}
			default -> LOG_STATE.notice(Component.translatable("mcchat.command.dict.usage"), ChatFormatting.YELLOW);
		}
	}

	/** /font [default|uniform|<namespace:id>] */
	private static void fontCommand(String[] args) {
		if (args.length < 2) {
			String cur = config.font.isEmpty() ? "default" : config.font;
			LOG_STATE.notice(Component.translatable("mcchat.command.font.current", cur), ChatFormatting.GRAY);
			return;
		}
		String arg = args[1].toLowerCase();
		if (arg.equals("system")) {
			systemFontCommand(args);
			return;
		}
		if (arg.equals("size")) {
			fontSizeCommand(args);
			return;
		}
		String id = switch (arg) {
			case "default", "reset" -> "";
			case "uniform" -> "minecraft:uniform";
			default -> arg;
		};
		if (!id.isEmpty() && Identifier.tryParse(id) == null) {
			LOG_STATE.notice(Component.translatable("mcchat.command.font.invalid", arg), ChatFormatting.YELLOW);
			return;
		}
		config.font = id;
		config.save();
		fontCache = null;
		LOG_STATE.notice(Component.translatable("mcchat.command.font.set", id.isEmpty() ? "default" : id),
				ChatFormatting.GREEN);
	}

	/** /font system [<file name> [glyph size]]: lists OS fonts, or installs one and switches to it. */
	private static void systemFontCommand(String[] args) {
		if (args.length < 3) {
			Map<String, Path> fonts = SystemFonts.list();
			LOG_STATE.notice(Component.translatable("mcchat.command.font.system.usage", fonts.size()), ChatFormatting.GRAY);
			return;
		}
		Path file = SystemFonts.find(args[2]);
		if (file == null) {
			LOG_STATE.notice(Component.translatable("mcchat.command.font.system.missing", args[2]), ChatFormatting.YELLOW);
			return;
		}
		if (args.length >= 4) {
			float glyph = parseFloat(args[3]);
			if (glyph < 4 || glyph > 32) {
				LOG_STATE.notice(Component.translatable("mcchat.command.font.system.glyph", config.systemFontSize),
						ChatFormatting.YELLOW);
				return;
			}
			config.systemFontSize = glyph;
		}
		config.systemFont = file.getFileName().toString();
		config.font = SystemFonts.FONT_ID;
		config.save();
		fontCache = null;
		installSystemFont(file);
	}

	/** /font size <percent>: text size of the mcchat screen log, for any font. */
	private static void fontSizeCommand(String[] args) {
		int pct = args.length >= 3 ? Math.round(parseFloat(args[2].replace("%", ""))) : -1;
		if (pct < McChatConfig.MIN_SCALE || pct > McChatConfig.MAX_SCALE) {
			LOG_STATE.notice(Component.translatable("mcchat.command.font.size.usage", config.textScale),
					ChatFormatting.YELLOW);
			return;
		}
		config.textScale = pct;
		config.save();
		LOG_STATE.notice(Component.translatable("mcchat.command.font.size.set", pct), ChatFormatting.GREEN);
	}

	private static float parseFloat(String s) {
		try {
			return Float.parseFloat(s);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Scale factor for the mcchat screen log. */
	public static float textScale() {
		return config.textScale / 100f;
	}

	private static void installSystemFont(Path file) {
		LOG_STATE.notice(Component.translatable("mcchat.command.font.system.loading", file.getFileName().toString()),
				ChatFormatting.GRAY);
		try {
			SystemFonts.install(file, config.systemFontSize, config.systemFontOversample)
					.whenComplete((v, e) -> Minecraft.getInstance().execute(() -> {
						if (e != null) {
							LOG.warn("mcchat: font reload failed", e);
							LOG_STATE.notice(Component.translatable("mcchat.command.font.system.failed", e.toString()),
									ChatFormatting.RED);
						} else {
							LOG_STATE.notice(Component.translatable("mcchat.command.font.set",
									file.getFileName().toString()), ChatFormatting.GREEN);
						}
					}));
		} catch (IOException | RuntimeException e) {
			LOG.warn("mcchat: could not install font {}", file, e);
			LOG_STATE.notice(Component.translatable("mcchat.command.font.system.failed", e.toString()), ChatFormatting.RED);
		}
	}

	private static String fontCacheKey = null;
	private static FontDescription fontCache = null;

	/** Applies the configured font to an mcchat line; unchanged with the default font. */
	public static Component withFont(Component c) {
		if (config.font.isEmpty()) return c;
		if (fontCache == null || !config.font.equals(fontCacheKey)) {
			Identifier id = Identifier.tryParse(config.font);
			if (id == null) return c;
			fontCacheKey = config.font;
			fontCache = new FontDescription.Resource(id);
		}
		FontDescription f = fontCache;
		return Component.empty().append(c).withStyle(s -> s.withFont(f));
	}

	@Override
	public void onInitializeClient() {
		connection = new ChatConnection(r -> Minecraft.getInstance().execute(r), LOG_STATE);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.mcchat.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.screen == null && client.player != null) client.setScreen(new McChatScreen());
			}
		});

		// The mcchat screen draws its own log; hide the vanilla chat HUD under it.
		HudElementRegistry.replaceElement(VanillaHudElements.CHAT, chat -> (g, tick) -> {
			if (!(Minecraft.getInstance().screen instanceof McChatScreen)) chat.render(g, tick);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> connection.stop());

		// For players who can still open the vanilla chat screen.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("mcc")
						.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
								.executes(ctx -> {
									submit(StringArgumentType.getString(ctx, "text"));
									return 1;
								}))
						.executes(ctx -> {
							Minecraft mc = Minecraft.getInstance();
							// Open after the chat screen running this command has closed.
							mc.execute(() -> mc.setScreen(new McChatScreen()));
							return 1;
						})));
	}

	private static void onJoin(Minecraft client) {
		McChatConfig cfg = McChatConfig.load();
		config = cfg;
		LOG_STATE.reset(cfg.showInChatHud);

		String channel = resolveChannel(client, cfg);
		if (channel == null) {
			connection.stop();
			return;
		}
		if (cfg.token.isEmpty()) {
			connection.stop();
			LOG_STATE.notice(Component.translatable("mcchat.error.no_token", McChatConfig.path().toString()),
					ChatFormatting.YELLOW);
			return;
		}
		URI uri = cfg.uri();
		if (uri == null) {
			connection.stop();
			LOG_STATE.notice(Component.translatable("mcchat.error.bad_url", cfg.serverUrl), ChatFormatting.RED);
			return;
		}
		connection.start(uri, cfg.token, channel);
	}

	/**
	 * The channel is the server address as typed in the server list; the
	 * server canonicalizes it. Singleplayer has no channel unless overridden.
	 */
	static String resolveChannel(Minecraft client, McChatConfig cfg) {
		if (!cfg.channelOverride.isEmpty()) return cfg.channelOverride;
		if (client.isLocalServer()) return null;
		ServerData server = client.getCurrentServer();
		if (server == null || server.ip == null || server.ip.isBlank()) return null;
		return server.ip.trim();
	}
}
