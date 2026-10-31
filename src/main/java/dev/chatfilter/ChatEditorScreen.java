package dev.chatfilter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Editor-Ansicht des Chats.
 * Linke Maustaste ziehen = Text markieren (blau), Doppelklick = Wort.
 * Rechte Maustaste ziehen (oder Knopf "Variable: AN" + ziehen) = variablen Teil markieren (orange).
 * Rechtsklick auf einen orangen Teil entfernt ihn wieder.
 */
public class ChatEditorScreen extends Screen {
	private static final int PANEL_W = 170;
	private static final int CHAT_LEFT = 10;
	private static final int CHAT_TOP = 24;

	private static final int COLOR_PHRASE = 0xFF3355AA;
	private static final int COLOR_VAR = 0xFFCC7722;

	private record VLine(String text, int msg, int start) {
	}

	private final List<String> messages = new ArrayList<>(ChatFilterClient.LOG);
	private final List<VLine> vlines = new ArrayList<>();
	private final List<int[]> vars = new ArrayList<>();

	private int chatRight;
	private int chatBottom;
	private int panelX;
	private int lineH;

	private int scroll;
	private int ruleScroll;
	private boolean firstInit = true;

	// Markierter Text (global = Zeichenposition in der ganzen Nachricht)
	private int selMsg = -1;
	private int selA;
	private int selB;

	// Laufendes Ziehen: 0 = nichts, 1 = Text, 2 = Variable
	private int dragKind;
	private int varA;
	private int varB;

	private boolean removeOnly;
	private boolean varMode;
	private Button modeButton;
	private Button varButton;

	// kleine Diagnose, damit man sieht, ob Klicks ankommen
	private int clickCount;
	private String lastClick = "-";

	// kurze Rückmeldung (Config gespeichert/geladen)
	private String status = "";
	private long statusUntil;

	public ChatEditorScreen() {
		super(Lang.c("editor.title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		lineH = this.font.lineHeight + 2;
		panelX = this.width - PANEL_W - 10;
		chatRight = panelX - 12;
		chatBottom = this.height - 86;

		rebuildLines();
		if (firstInit) {
			scroll = Math.max(0, vlines.size() - visibleLines());
			firstInit = false;
		}

		int y = this.height - 28;
		modeButton = this.addRenderableWidget(Button.builder(modeLabel(), b -> {
			removeOnly = !removeOnly;
			b.setMessage(modeLabel());
		}).bounds(10, y, 170, 20).build());

		varButton = this.addRenderableWidget(Button.builder(varLabel(), b -> {
			varMode = !varMode;
			b.setMessage(varLabel());
		}).bounds(185, y, 120, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("editor.add_rule"), b -> addRule())
				.bounds(310, y, 110, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("editor.done"), b -> this.onClose())
				.bounds(425, y, 60, 20).build());

		// zweite Reihe: Config speichern / laden / Ordner öffnen
		int y2 = this.height - 52;
		this.addRenderableWidget(Button.builder(Lang.c("editor.save"), b -> saveConfig())
				.bounds(10, y2, 120, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("common.load"),
						b -> this.minecraft.gui.setScreen(new ConfigPickerScreen(this)))
				.bounds(135, y2, 100, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("common.open_folder"), b -> FilterConfig.openFolder())
				.bounds(240, y2, 140, 20).build());

		this.addRenderableWidget(Button.builder(emptyLabel(), b -> {
			FilterConfig.blockEmpty = !FilterConfig.blockEmpty;
			FilterConfig.saveSettings();
			b.setMessage(emptyLabel());
		}).bounds(385, y2, 130, 20).build());
	}

	private Component emptyLabel() {
		return Lang.c(FilterConfig.blockEmpty ? "editor.empty_blocked" : "editor.empty_visible");
	}

	void setStatus(String text) {
		status = text;
		statusUntil = System.currentTimeMillis() + 7000;
	}

	private void saveConfig() {
		try {
			setStatus(Lang.t("editor.saved", FilterConfig.exportNew().getFileName()));
		} catch (Exception e) {
			setStatus(Lang.t("editor.save_failed", e.getMessage()));
		}
	}

	private Component modeLabel() {
		return Lang.c(removeOnly ? "editor.mode_text" : "editor.mode_message");
	}

	private Component varLabel() {
		return Lang.c(varMode ? "editor.var_on" : "editor.var_off");
	}

	// ---------- Zeilenumbruch ----------

	private int visibleLines() {
		return Math.max(1, (chatBottom - CHAT_TOP) / lineH);
	}

	private int fit(String s, int maxW) {
		int end = 1;
		while (end < s.length() && this.font.width(s.substring(0, end + 1)) <= maxW) {
			end++;
		}
		if (end >= s.length()) {
			return s.length();
		}
		int space = s.lastIndexOf(' ', end - 1);
		return space > 0 ? space + 1 : end;
	}

	private void rebuildLines() {
		vlines.clear();
		int maxW = chatRight - CHAT_LEFT - 6;
		for (int m = 0; m < messages.size(); m++) {
			String rest = messages.get(m);
			int offset = 0;
			while (!rest.isEmpty()) {
				int end = fit(rest, maxW);
				vlines.add(new VLine(rest.substring(0, end), m, offset));
				offset += end;
				rest = rest.substring(end);
			}
		}
	}

	// ---------- Hilfsfunktionen ----------

	private boolean inChat(double mx, double my) {
		return mx >= CHAT_LEFT && mx <= chatRight && my >= CHAT_TOP && my <= chatBottom;
	}

	private int lineAt(double y) {
		if (vlines.isEmpty()) {
			return -1;
		}
		int idx = scroll + (int) Math.floor((y - CHAT_TOP) / lineH);
		int max = Math.min(vlines.size() - 1, scroll + visibleLines() - 1);
		return Math.max(scroll, Math.min(idx, max));
	}

	private int offsetAt(VLine line, double x) {
		double rel = x - (CHAT_LEFT + 2);
		String t = line.text();
		for (int i = 0; i < t.length(); i++) {
			int w0 = this.font.width(t.substring(0, i));
			int w1 = this.font.width(t.substring(0, i + 1));
			if (rel < (w0 + w1) / 2.0) {
				return i;
			}
		}
		return t.length();
	}

	private void selectWord(int msg, int pos) {
		String s = messages.get(msg);
		int a = Math.min(pos, s.length());
		int b = a;
		while (a > 0 && !Character.isWhitespace(s.charAt(a - 1))) {
			a--;
		}
		while (b < s.length() && !Character.isWhitespace(s.charAt(b))) {
			b++;
		}
		selMsg = msg;
		selA = a;
		selB = b;
	}

	/** Markierter Bereich ohne Leerzeichen an den Rändern: {Start, Ende} oder null. */
	private int[] phraseRange() {
		if (selMsg < 0 || selMsg >= messages.size()) {
			return null;
		}
		String s = messages.get(selMsg);
		int a = Math.max(0, Math.min(Math.min(selA, selB), s.length()));
		int b = Math.max(0, Math.min(Math.max(selA, selB), s.length()));
		while (a < b && Character.isWhitespace(s.charAt(a))) {
			a++;
		}
		while (b > a && Character.isWhitespace(s.charAt(b - 1))) {
			b--;
		}
		return a < b ? new int[]{a, b} : null;
	}

	/** Baut aus Auswahl + variablen Teilen das Regel-Muster, z.B. "{*} hat {*} verkauft". */
	private String buildPattern() {
		int[] range = phraseRange();
		if (range == null) {
			return "";
		}
		String s = messages.get(selMsg);
		int a = range[0];
		int b = range[1];

		List<int[]> clipped = new ArrayList<>();
		for (int[] v : vars) {
			int x = Math.max(a, v[0]);
			int y = Math.min(b, v[1]);
			if (x < y) {
				clipped.add(new int[]{x, y});
			}
		}
		clipped.sort(Comparator.comparingInt(v -> v[0]));

		List<int[]> merged = new ArrayList<>();
		for (int[] v : clipped) {
			if (!merged.isEmpty() && v[0] <= merged.get(merged.size() - 1)[1]) {
				int[] last = merged.get(merged.size() - 1);
				last[1] = Math.max(last[1], v[1]);
			} else {
				merged.add(v);
			}
		}

		StringBuilder sb = new StringBuilder();
		int pos = a;
		for (int[] v : merged) {
			sb.append(s, pos, v[0]);
			String example = s.substring(v[0], v[1]);
			boolean hasSpace = example.chars().anyMatch(Character::isWhitespace);
			sb.append(hasSpace ? FilterConfig.VAR_ANY : FilterConfig.VAR_WORD);
			pos = v[1];
		}
		sb.append(s, pos, b);
		return sb.toString();
	}

	private void addRule() {
		String pattern = buildPattern();
		if (pattern.isBlank() || FilterConfig.isOnlyVariables(pattern)) {
			return;
		}
		boolean found = false;
		for (FilterRule existing : FilterConfig.RULES) {
			if (existing.text.equalsIgnoreCase(pattern)) {
				existing.removeOnlyText = removeOnly;
				found = true;
			}
		}
		if (!found) {
			FilterConfig.RULES.add(new FilterRule(pattern, removeOnly));
		}
		FilterConfig.save();
		selMsg = -1;
		vars.clear();
		dragKind = 0;
	}

	private String trim(String s, int maxW) {
		if (this.font.width(s) <= maxW) {
			return s;
		}
		while (!s.isEmpty() && this.font.width(s + "...") > maxW) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "...";
	}

	private int ruleRowH() {
		return lineH * 2 + 4;
	}

	// ---------- Eingabe ----------

	private void updateDrag(double mx, double my) {
		if (dragKind == 0) {
			return;
		}
		int li = lineAt(my);
		if (li < 0) {
			return;
		}
		VLine line = vlines.get(li);
		if (line.msg() != selMsg) {
			return;
		}
		int global = line.start() + offsetAt(line, mx);
		if (dragKind == 1) {
			selB = global;
		} else {
			varB = global;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		clickCount++;
		lastClick = (int) mx + "," + (int) my;

		if (inChat(mx, my)) {
			int li = lineAt(my);
			if (li < 0) {
				return true;
			}
			VLine line = vlines.get(li);
			int global = line.start() + offsetAt(line, mx);
			boolean wantVar = event.button() == 1 || varMode;

			if (wantVar) {
				// variable Teile nur innerhalb der bereits markierten Nachricht
				if (selMsg == line.msg()) {
					dragKind = 2;
					varA = global;
					varB = global;
				}
				return true;
			}

			if (doubleClick) {
				selectWord(line.msg(), global);
				vars.clear();
				dragKind = 0;
			} else {
				selMsg = line.msg();
				selA = global;
				selB = global;
				vars.clear();
				dragKind = 1;
			}
			return true;
		}

		if (mx >= panelX && my >= CHAT_TOP && my <= chatBottom) {
			int row = ruleScroll + (int) ((my - CHAT_TOP) / ruleRowH());
			if (row >= 0 && row < FilterConfig.RULES.size()) {
				double local = (my - CHAT_TOP) - (row - ruleScroll) * ruleRowH();
				if (local < lineH + 2) {
					if (mx <= panelX + 6 + this.font.width("[x]")) {
						FilterConfig.RULES.remove(row);
						FilterConfig.save();
						ruleScroll = Math.max(0, Math.min(ruleScroll, FilterConfig.RULES.size() - 1));
					}
				} else {
					FilterRule rule = FilterConfig.RULES.get(row);
					rule.removeOnlyText = !rule.removeOnlyText;
					FilterConfig.save();
				}
			}
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragKind != 0) {
			updateDrag(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragKind == 2) {
			int a = Math.min(varA, varB);
			int b = Math.max(varA, varB);
			if (a == b) {
				// Klick ohne Ziehen: orangen Teil an dieser Stelle entfernen
				vars.removeIf(v -> a >= v[0] && a <= v[1]);
			} else {
				vars.add(new int[]{a, b});
			}
		}
		boolean handled = dragKind != 0;
		dragKind = 0;
		return handled || super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= panelX) {
			int maxRules = Math.max(0, FilterConfig.RULES.size() - (chatBottom - CHAT_TOP) / ruleRowH());
			ruleScroll = Math.max(0, Math.min(maxRules, ruleScroll - (int) Math.signum(scrollY)));
		} else {
			int max = Math.max(0, vlines.size() - visibleLines());
			scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY) * 3));
		}
		return true;
	}

	// ---------- Zeichnen ----------

	private void highlight(GuiGraphicsExtractor g, VLine line, int a, int b, int y, int color) {
		int from = Math.max(a, line.start());
		int to = Math.min(b, line.start() + line.text().length());
		if (from < to) {
			int x1 = CHAT_LEFT + 2 + this.font.width(line.text().substring(0, from - line.start()));
			int x2 = CHAT_LEFT + 2 + this.font.width(line.text().substring(0, to - line.start()));
			g.fill(x1, y - 1, x2, y + this.font.lineHeight + 1, color);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// Ziehen auch über die Mausposition pro Frame verfolgen (unabhängig von mouseDragged)
		updateDrag(mouseX, mouseY);

		super.extractRenderState(g, mouseX, mouseY, delta);

		g.text(this.font, Lang.t("editor.help"), 10, 8, 0xFFFFFFFF, true);

		// Chat-Bereich
		g.fill(CHAT_LEFT - 2, CHAT_TOP - 2, chatRight + 2, chatBottom + 2, 0xAA000000);
		int vis = visibleLines();
		for (int i = 0; i < vis && scroll + i < vlines.size(); i++) {
			VLine line = vlines.get(scroll + i);
			int y = CHAT_TOP + i * lineH;

			if (line.msg() == selMsg) {
				highlight(g, line, Math.min(selA, selB), Math.max(selA, selB), y, COLOR_PHRASE);
				for (int[] v : vars) {
					highlight(g, line, v[0], v[1], y, COLOR_VAR);
				}
				if (dragKind == 2) {
					highlight(g, line, Math.min(varA, varB), Math.max(varA, varB), y, COLOR_VAR);
				}
			}

			boolean affected = FilterConfig.isAffected(messages.get(line.msg()));
			g.text(this.font, line.text(), CHAT_LEFT + 2, y, affected ? 0xFF777777 : 0xFFFFFFFF, true);
		}
		if (vlines.isEmpty()) {
			g.text(this.font, Lang.t("editor.no_messages"), CHAT_LEFT + 2, CHAT_TOP + 2, 0xFFAAAAAA, true);
		}

		// Regel-Panel
		g.fill(panelX - 2, CHAT_TOP - 2, panelX + PANEL_W + 2, chatBottom + 2, 0xAA000000);
		g.text(this.font, Lang.t("editor.rules", FilterConfig.RULES.size()), panelX, 8, 0xFFFFFFFF, true);
		int rowH = ruleRowH();
		int rows = (chatBottom - CHAT_TOP) / rowH;
		for (int i = 0; i < rows && ruleScroll + i < FilterConfig.RULES.size(); i++) {
			FilterRule rule = FilterConfig.RULES.get(ruleScroll + i);
			int y = CHAT_TOP + i * rowH;
			g.text(this.font, "[x]", panelX + 4, y, 0xFFFF5555, true);
			int tx = panelX + 8 + this.font.width("[x]");
			String shown = "\"" + FilterConfig.display(rule.text) + "\"";
			g.text(this.font, trim(shown, PANEL_W - (tx - panelX) - 4), tx, y, 0xFFFFFFFF, true);
			g.text(this.font, Lang.t(rule.removeOnlyText ? "editor.rule_text" : "editor.rule_message"),
					panelX + 4, y + lineH, 0xFF999999, true);
		}

		// Vorschau der entstehenden Regel
		String pattern = buildPattern();
		String preview = pattern.isEmpty() ? "-" : "\"" + FilterConfig.display(pattern) + "\"";
		g.text(this.font, Lang.t("editor.rule_preview", "") + trim(preview, this.width - 160), 10, this.height - 80, 0xFFFFFF55, true);

		if (System.currentTimeMillis() < statusUntil) {
			g.text(this.font, trim(status, this.width - 20), 10, this.height - 68, 0xFF55FF55, true);
		}

		// Diagnose (kann später entfernt werden)
		g.text(this.font, Lang.t("editor.clicks", clickCount, lastClick), this.width - 130, this.height - 80, 0xFF666666, true);
	}
}
