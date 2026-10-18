package dev.daruks.mcchat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * Chat window opened with the mcchat key. Works even when vanilla chat is
 * unavailable. It shows the log itself and hides the vanilla chat HUD while
 * open (see {@link McChatClient}) so the two do not overlap.
 */
public final class McChatScreen extends Screen {
	private static final int MARGIN = 4;
	private static final int INPUT_HEIGHT = 12;
	// Colors must include alpha since 1.21.6.
	private static final int BG = 0x80000000;
	/** Log width cap, about vanilla chat at its widest, so the game stays visible. */
	private static final int MAX_TEXT_WIDTH = 480;
	private static final int VISIBLE_LINES = 12;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFAAAAAA;

	private EditBox input;
	private String draft = "";
	/** Wrapped lines scrolled up from the newest; 0 follows new messages. */
	private int scroll = 0;
	/** Wrapped line count and visible lines from the last frame, for clamping scroll. */
	private int lastTotal = 0;
	private int lastShown = VISIBLE_LINES;

	public McChatScreen() {
		super(Component.translatable("mcchat.screen.title"));
	}

	@Override
	protected void init() {
		input = new EditBox(font, MARGIN, height - INPUT_HEIGHT - MARGIN, width - MARGIN * 2, INPUT_HEIGHT,
				Component.translatable("mcchat.screen.input"));
		input.setMaxLength(256);
		input.setValue(draft);
		addRenderableWidget(input);
		setInitialFocus(input);
	}

	@Override
	public void resize(int w, int h) {
		draft = input.getValue();
		super.resize(w, h);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			String text = input.getValue().trim();
			if (!text.isEmpty() && McChatClient.send(text)) {
				input.setValue("");
				scroll = 0;
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_PAGE_UP) {
			scrollBy(lastShown - 1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
			scrollBy(-(lastShown - 1));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollBy((int) Math.signum(scrollY));
		return true;
	}

	private void scrollBy(int lines) {
		scroll = Math.max(0, Math.min(scroll + lines, Math.max(0, lastTotal - lastShown)));
	}

	/** Puts a rate-limited message back so it does not have to be retyped. */
	@Override
	public void tick() {
		super.tick();
		String rejected = McChatClient.log().takeRejected();
		if (rejected != null && input.getValue().isEmpty()) input.setValue(rejected);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** No backdrop: like vanilla chat, only the log area is shaded so the game stays visible. */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		ChatLog log = McChatClient.log();

		Component status = statusLine(log);
		g.fill(0, 0, font.width(status) + MARGIN * 2, font.lineHeight + MARGIN * 2, BG);
		g.drawString(font, status, MARGIN, MARGIN, DIM, false);

		// Lines are drawn bottom-up above the input box, newest at the bottom.
		int textWidth = Math.min(width - MARGIN * 2, MAX_TEXT_WIDTH);
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (ChatConnection.Message m : log.lines()) {
			wrapped.addAll(font.split(ChatLog.format(m), textWidth));
		}
		int step = font.lineHeight + 1;
		int bottom = height - INPUT_HEIGHT - MARGIN * 2 - font.lineHeight;
		int top = MARGIN * 3 + font.lineHeight;
		// A recent notice (e.g. rate limited) takes the line right above the input.
		Component notice = log.recentNotice();
		int noticeY = bottom;
		if (notice != null) bottom -= step;
		int capacity = Math.max(1, Math.min(VISIBLE_LINES, (bottom - top) / step + 1));
		lastTotal = wrapped.size();
		lastShown = capacity;
		scroll = Math.max(0, Math.min(scroll, wrapped.size() - capacity));
		int end = wrapped.size() - scroll;
		int shown = Math.min(end, capacity);
		if (shown > 0 || notice != null) {
			int panelTop = (shown > 0 ? bottom - (shown - 1) * step : noticeY) - 2;
			g.fill(0, panelTop, textWidth + MARGIN * 2, height - INPUT_HEIGHT - MARGIN - 1, BG);
		}
		if (notice != null) g.drawString(font, notice, MARGIN, noticeY, TEXT, true);
		int y = bottom;
		for (int i = end - 1; i >= end - shown; i--) {
			g.drawString(font, wrapped.get(i), MARGIN, y, TEXT, true);
			y -= step;
		}
		if (scroll > 0) {
			Component more = Component.translatable("mcchat.screen.scrolled", scroll);
			g.drawString(font, more, textWidth + MARGIN - font.width(more), bottom, DIM, true);
		}
	}

	private static Component statusLine(ChatLog log) {
		String ch = log.channel() != null ? log.channel() : "-";
		String name = log.myName().isEmpty() ? "-" : log.myName();
		Component st = Component.translatable("mcchat.status." + log.status().name().toLowerCase());
		return Component.translatable("mcchat.screen.status", st, ch, name);
	}
}
