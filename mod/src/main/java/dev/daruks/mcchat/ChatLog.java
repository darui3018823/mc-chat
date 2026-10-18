package dev.daruks.mcchat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Client-side chat state. Receives {@link ChatConnection} events on the
 * client thread, keeps recent lines for {@link McChatScreen}, and mirrors
 * them into the vanilla chat HUD.
 */
public final class ChatLog implements ChatConnection.Listener {
	private static final int MAX_LINES = 200;
	/** How many missed lines to echo into the HUD on reconnect. */
	private static final int HUD_HISTORY = 10;

	private final Deque<ChatConnection.Message> lines = new ArrayDeque<>();
	private ChatConnection.Status status = ChatConnection.Status.IDLE;
	private String statusDetail = "";
	private String myName = "";
	/** Canonical channel name as returned by the server. */
	private String channel = null;
	private long lastId = 0;
	private boolean everConnected = false;
	private boolean showInHud = true;

	public void reset(boolean showInHud) {
		lines.clear();
		channel = null;
		lastId = 0;
		everConnected = false;
		this.showInHud = showInHud;
	}

	public Iterable<ChatConnection.Message> lines() {
		return lines;
	}

	public ChatConnection.Status status() {
		return status;
	}

	public String statusDetail() {
		return statusDetail;
	}

	public String channel() {
		return channel;
	}

	public String myName() {
		return myName;
	}

	@Override
	public void onStatus(ChatConnection.Status s, String detail) {
		ChatConnection.Status prev = status;
		status = s;
		statusDetail = detail == null ? "" : detail;
		switch (s) {
			case AUTH_FAILED -> notice(Component.translatable("mcchat.status.auth_failed"), ChatFormatting.RED);
			case REVOKED -> notice(Component.translatable("mcchat.status.revoked"), ChatFormatting.RED);
			case RECONNECTING -> {
				if (prev == ChatConnection.Status.CONNECTED) {
					notice(Component.translatable("mcchat.status.lost"), ChatFormatting.GRAY);
				}
			}
			case CONNECTED -> {
				if (everConnected) notice(Component.translatable("mcchat.status.reconnected"), ChatFormatting.GRAY);
				everConnected = true;
			}
			default -> {}
		}
	}

	@Override
	public void onHello(String name) {
		myName = name;
	}

	@Override
	public void onHistory(String ch, List<ChatConnection.Message> messages) {
		boolean first = !ch.equals(channel);
		if (first) {
			lines.clear();
			lastId = 0;
		}
		channel = ch;
		// On the first join the backlog only goes to the screen (Y); on a reconnect
		// the HUD gets what was missed while disconnected.
		int skip = first ? Integer.MAX_VALUE
				: Math.max(0, (int) messages.stream().filter(m -> m.id() > lastId).count() - HUD_HISTORY);
		for (ChatConnection.Message m : messages) {
			if (m.id() <= lastId) continue;
			add(m);
			if (skip > 0) {
				skip--;
			} else {
				toHud(m);
			}
		}
	}

	@Override
	public void onMessage(ChatConnection.Message m) {
		if (channel == null || !channel.equals(m.channel()) || m.id() <= lastId) return;
		add(m);
		toHud(m);
	}

	@Override
	public void onError(String code) {
		switch (code) {
			case "rate_limited" -> notice(Component.translatable("mcchat.error.rate_limited"), ChatFormatting.YELLOW);
			case "empty" -> {}
			default -> notice(Component.translatable("mcchat.error.other", code), ChatFormatting.YELLOW);
		}
	}

	private void add(ChatConnection.Message m) {
		lines.addLast(m);
		while (lines.size() > MAX_LINES) lines.removeFirst();
		lastId = Math.max(lastId, m.id());
	}

	/** "[MCChat] name: text" with the prefix and name colored. */
	public static Component format(ChatConnection.Message m) {
		return prefix()
				.append(Component.literal(m.name()).withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(": " + m.text()).withStyle(ChatFormatting.WHITE));
	}

	private static MutableComponent prefix() {
		return Component.literal("[MCChat] ").withStyle(ChatFormatting.AQUA);
	}

	private void toHud(ChatConnection.Message m) {
		if (showInHud) hud(format(m));
	}

	/** Status notices always go to the HUD so they are not missed. */
	public void notice(Component c, ChatFormatting color) {
		hud(prefix().append(c.copy().withStyle(color)));
	}

	private static void hud(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) mc.gui.getChat().addMessage(c);
	}
}
