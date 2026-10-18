package dev.daruks.mcchat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
	/** How long the latest notice stays visible in {@link McChatScreen}. */
	private static final long NOTICE_MILLIS = 5000;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

	/** Formatted lines for the screen: messages and local info such as /dict list. */
	private final Deque<Component> lines = new ArrayDeque<>();
	private ChatConnection.Status status = ChatConnection.Status.IDLE;
	private String statusDetail = "";
	private String myName = "";
	/** Canonical channel name as returned by the server. */
	private String channel = null;
	private long lastId = 0;
	private boolean everConnected = false;
	private boolean showInHud = true;
	private Component lastNotice = null;
	private long lastNoticeAt = 0;
	/** The latest notice is the rate limit one, which a later successful send clears. */
	private boolean noticeIsRateLimit = false;
	/**
	 * Texts we sent that the server has not answered yet, oldest first. The
	 * server answers each send in order, either with our own msg or an error.
	 */
	private final Deque<String> pending = new ArrayDeque<>();
	private String rejected = null;
	private long rejectedAt = 0;

	public void reset(boolean showInHud) {
		lines.clear();
		channel = null;
		lastId = 0;
		everConnected = false;
		lastNotice = null;
		pending.clear();
		rejected = null;
		this.showInHud = showInHud;
	}

	public Iterable<Component> lines() {
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

	/** The latest notice if it is still recent, else null. The screen hides the HUD, so it shows this itself. */
	public Component recentNotice() {
		if (lastNotice == null || System.currentTimeMillis() - lastNoticeAt > NOTICE_MILLIS) return null;
		return lastNotice;
	}

	/** Records a text handed to the connection, to match it with the server's answer. */
	public void sent(String text) {
		pending.addLast(text);
	}

	/** Returns a recently rate-limited text once, so the screen can put it back. */
	public String takeRejected() {
		String r = rejected;
		rejected = null;
		if (r == null || System.currentTimeMillis() - rejectedAt > NOTICE_MILLIS) return null;
		return r;
	}

	@Override
	public void onStatus(ChatConnection.Status s, String detail) {
		ChatConnection.Status prev = status;
		// Unanswered sends on a dropped socket get no reply.
		if (s != ChatConnection.Status.CONNECTED) pending.clear();
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
		if (channel == null || !channel.equals(m.channel())) return;
		if (m.name().equals(myName)) {
			pending.pollFirst();
			if (noticeIsRateLimit) lastNotice = null;
		}
		if (m.id() <= lastId) return;
		add(m);
		toHud(m);
	}

	@Override
	public void onError(String code) {
		switch (code) {
			case "rate_limited" -> {
				String text = pending.pollFirst();
				if (text != null) {
					rejected = text;
					rejectedAt = System.currentTimeMillis();
				}
				notice(Component.translatable("mcchat.error.rate_limited"), ChatFormatting.YELLOW);
				noticeIsRateLimit = true;
			}
			case "empty" -> pending.pollFirst();
			default -> {
				if (!code.equals("bad_request") && !code.equals("bad_channel")) pending.pollFirst();
				notice(Component.translatable("mcchat.error.other", code), ChatFormatting.YELLOW);
			}
		}
	}

	private void add(ChatConnection.Message m) {
		addLine(format(m));
		lastId = Math.max(lastId, m.id());
	}

	private void addLine(Component c) {
		lines.addLast(c);
		while (lines.size() > MAX_LINES) lines.removeFirst();
	}

	/** A local line (not from the server) shown in both the screen log and the HUD. */
	public void info(Component c) {
		Component line = prefix().append(c);
		addLine(line);
		hud(line);
	}

	/** "[MCChat][yyyy/MM/dd HH:mm] name: text" in local time, with the parts colored. */
	public static Component format(ChatConnection.Message m) {
		String time = TIME.format(Instant.ofEpochMilli(m.ts()).atZone(ZoneId.systemDefault()));
		return Component.literal("[MCChat]").withStyle(ChatFormatting.AQUA)
				.append(Component.literal("[" + time + "] ").withStyle(ChatFormatting.GRAY))
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
		lastNotice = c.copy().withStyle(color);
		lastNoticeAt = System.currentTimeMillis();
		noticeIsRateLimit = false;
		hud(prefix().append(lastNotice));
	}

	private static void hud(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) mc.gui.getChat().addMessage(McChatClient.withFont(c));
	}
}
