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
	private static final int SUGGEST_BG = 0xE0000000;
	private static final int SUGGEST_CMD = 0xFFFFFF55;
	private static final int MAX_FONT_SUGGESTIONS = 10;

	/** Local commands handled by {@link McChatClient#submit}: completion and hint key. */
	private record Suggestion(String completion, String hint) {}

	private static final List<Suggestion> SUGGESTIONS = List.of(
			new Suggestion("/jp ", "mcchat.suggest.jp"),
			new Suggestion("/japanize", "mcchat.suggest.japanize"),
			new Suggestion("/japanize on", "mcchat.suggest.japanize.on"),
			new Suggestion("/japanize off", "mcchat.suggest.japanize.off"),
			new Suggestion("/dict add ", "mcchat.suggest.dict.add"),
			new Suggestion("/dict remove ", "mcchat.suggest.dict.remove"),
			new Suggestion("/dict list", "mcchat.suggest.dict.list"),
			new Suggestion("/font default", "mcchat.suggest.font.default"),
			new Suggestion("/font uniform", "mcchat.suggest.font.uniform"),
			new Suggestion("/font system ", "mcchat.suggest.font.system"),
			new Suggestion("/font size ", "mcchat.suggest.font.size"),
			new Suggestion("/font ", "mcchat.suggest.font"));

	private static final int MAX_HISTORY = 50;
	/** Lines submitted this session, newest last; kept across screen openings. */
	private static final List<String> HISTORY = new ArrayList<>();

	private EditBox input;
	/** Position in HISTORY while browsing with Up/Down, or -1 when editing a new line. */
	private int historyPos = -1;
	/** The new line being typed before browsing history started. */
	private String historyDraft = "";
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
			if (!text.isEmpty() && McChatClient.submit(text)) {
				remember(text);
				input.setValue("");
				scroll = 0;
			}
			return true;
		}
		// Esc first clears the input; a second Esc closes the screen.
		if (key == GLFW.GLFW_KEY_ESCAPE && !input.getValue().isEmpty()) {
			input.setValue("");
			historyPos = -1;
			return true;
		}
		if (key == GLFW.GLFW_KEY_UP) {
			browseHistory(1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_DOWN) {
			browseHistory(-1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_TAB) {
			List<Suggestion> s = suggestions();
			if (!s.isEmpty()) input.setValue(s.get(0).completion());
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

	private void remember(String text) {
		if (HISTORY.isEmpty() || !HISTORY.getLast().equals(text)) HISTORY.add(text);
		if (HISTORY.size() > MAX_HISTORY) HISTORY.removeFirst();
		historyPos = -1;
	}

	/** Moves through sent lines like vanilla chat: older with +1, newer with -1. */
	private void browseHistory(int dir) {
		int pos = Math.max(-1, Math.min(historyPos + dir, HISTORY.size() - 1));
		if (pos == historyPos) return;
		if (historyPos == -1) historyDraft = input.getValue();
		historyPos = pos;
		input.setValue(pos == -1 ? historyDraft : HISTORY.get(HISTORY.size() - 1 - pos));
		input.moveCursorToEnd(false);
	}

	/** Commands starting with what has been typed, while a command is being typed. */
	private List<Suggestion> suggestions() {
		String v = input.getValue();
		if (!v.startsWith("/") || v.startsWith("//")) return List.of();
		String typed = v.toLowerCase();
		List<Suggestion> out = new ArrayList<>();
		// OS font file names once "/font system " is typed.
		String fontPrefix = "/font system ";
		if (typed.startsWith(fontPrefix)) {
			String name = typed.substring(fontPrefix.length());
			for (String f : SystemFonts.list().keySet()) {
				if (f.startsWith(name) && !f.equals(name)) out.add(new Suggestion(fontPrefix + f, "mcchat.suggest.font.file"));
				if (out.size() >= MAX_FONT_SUGGESTIONS) break;
			}
			if (!out.isEmpty()) return out;
		}
		for (Suggestion s : SUGGESTIONS) {
			String c = s.completion();
			// "/jp " stays listed while its argument is typed; the rest while they still match.
			boolean match = c.endsWith(" ")
					? typed.startsWith(c) || c.startsWith(typed)
					: c.startsWith(typed);
			if (match) out.add(s);
		}
		return out;
	}

	private void renderSuggestions(GuiGraphics g) {
		List<Suggestion> list = suggestions();
		if (list.isEmpty()) return;
		int step = font.lineHeight + 1;
		int w = 0;
		List<Component> lines = new ArrayList<>();
		for (Suggestion s : list) {
			Component c = Component.literal(s.completion().trim()).withColor(SUGGEST_CMD)
					.append(Component.literal("  ").append(Component.translatable(s.hint())).withColor(DIM));
			lines.add(c);
			w = Math.max(w, font.width(c));
		}
		int bottom = height - INPUT_HEIGHT - MARGIN - 1;
		int top = bottom - lines.size() * step - 2;
		g.fill(MARGIN, top, MARGIN + w + 4, bottom, SUGGEST_BG);
		int y = top + 2;
		for (Component c : lines) {
			g.drawString(font, c, MARGIN + 2, y, TEXT, false);
			y += step;
		}
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

		// The log is drawn scaled by /font size; coordinates below are in scaled units.
		float k = McChatClient.textScale();
		int inputTop = (int) ((height - INPUT_HEIGHT - MARGIN) / k);
		int statusBottom = (int) ((font.lineHeight + MARGIN * 2) / k);
		g.pose().pushMatrix();
		g.pose().scale(k, k);

		// Lines are drawn bottom-up above the input box, newest at the bottom.
		int textWidth = (int) (Math.min(width - MARGIN * 2, MAX_TEXT_WIDTH) / k);
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (Component line : log.lines()) {
			wrapped.addAll(font.split(McChatClient.withFont(line), textWidth));
		}
		int step = font.lineHeight + 1;
		int bottom = inputTop - MARGIN - font.lineHeight;
		int top = statusBottom + MARGIN;
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
			g.fill(0, panelTop, textWidth + MARGIN * 2, inputTop - 1, BG);
		}
		if (notice != null) g.drawString(font, McChatClient.withFont(notice), MARGIN, noticeY, TEXT, true);
		int y = bottom;
		for (int i = end - 1; i >= end - shown; i--) {
			g.drawString(font, wrapped.get(i), MARGIN, y, TEXT, true);
			y -= step;
		}
		if (scroll > 0) {
			Component more = Component.translatable("mcchat.screen.scrolled", scroll);
			g.drawString(font, more, textWidth + MARGIN - font.width(more), bottom, DIM, true);
		}
		g.pose().popMatrix();
		renderSuggestions(g);
	}

	private static Component statusLine(ChatLog log) {
		String ch = log.channel() != null ? log.channel() : "-";
		String name = log.myName().isEmpty() ? "-" : log.myName();
		Component st = Component.translatable("mcchat.status." + log.status().name().toLowerCase());
		return Component.translatable("mcchat.screen.status", st, ch, name);
	}
}
