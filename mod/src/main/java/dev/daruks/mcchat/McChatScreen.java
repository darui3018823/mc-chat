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
 * unavailable, and shows the log itself in case the chat HUD is hidden.
 */
public final class McChatScreen extends Screen {
	private static final int MARGIN = 4;
	private static final int INPUT_HEIGHT = 12;
	// Colors must include alpha since 1.21.6.
	private static final int BG = 0x90000000;
	// Nearly opaque so the vanilla chat HUD and hotbar underneath do not show through.
	private static final int PANEL = 0xE0000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFAAAAAA;

	private EditBox input;
	private String draft = "";

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
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Plain translucent background; no blur so the game stays visible. */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, BG);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		ChatLog log = McChatClient.log();

		g.drawString(font, statusLine(log), MARGIN, MARGIN, DIM, false);

		// Lines are drawn bottom-up above the input box, newest at the bottom.
		int textWidth = width - MARGIN * 2;
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (ChatConnection.Message m : log.lines()) {
			wrapped.addAll(font.split(ChatLog.format(m), textWidth));
		}
		int step = font.lineHeight + 1;
		int bottom = height - INPUT_HEIGHT - MARGIN * 2 - font.lineHeight;
		int top = MARGIN * 2 + font.lineHeight;
		// A recent notice (e.g. rate limited) takes the line right above the input.
		Component notice = log.recentNotice();
		int noticeY = bottom;
		if (notice != null) bottom -= step;
		int shown = Math.min(wrapped.size(), (bottom - top) / step + 1);
		if (shown > 0 || notice != null) {
			int panelTop = (shown > 0 ? bottom - (shown - 1) * step : noticeY) - 2;
			g.fill(0, panelTop, width, height - INPUT_HEIGHT - MARGIN - 1, PANEL);
		}
		if (notice != null) g.drawString(font, notice, MARGIN, noticeY, TEXT, true);
		int y = bottom;
		for (int i = wrapped.size() - 1; i >= wrapped.size() - shown; i--) {
			g.drawString(font, wrapped.get(i), MARGIN, y, TEXT, true);
			y -= step;
		}
	}

	private static Component statusLine(ChatLog log) {
		String ch = log.channel() != null ? log.channel() : "-";
		String name = log.myName().isEmpty() ? "-" : log.myName();
		Component st = Component.translatable("mcchat.status." + log.status().name().toLowerCase());
		return Component.translatable("mcchat.screen.status", st, ch, name);
	}
}
