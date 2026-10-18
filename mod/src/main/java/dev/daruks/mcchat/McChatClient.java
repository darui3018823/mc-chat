package dev.daruks.mcchat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.net.URI;
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
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McChatClient implements ClientModInitializer {
	public static final String MOD_ID = "mcchat";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static final ChatLog LOG_STATE = new ChatLog();
	private static ChatConnection connection;
	private static KeyMapping openKey;

	public static ChatLog log() {
		return LOG_STATE;
	}

	/** Sends text to the current channel; notifies the player on failure. */
	public static boolean send(String text) {
		if (connection != null && connection.send(text)) {
			LOG_STATE.sent(text);
			return true;
		}
		LOG_STATE.notice(Component.translatable("mcchat.error.not_connected"), ChatFormatting.YELLOW);
		return false;
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
									send(StringArgumentType.getString(ctx, "text"));
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
