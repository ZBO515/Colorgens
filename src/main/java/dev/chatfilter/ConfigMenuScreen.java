package dev.chatfilter;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Einfacher Startbildschirm (Taste G): Liste aller Configs, "Config-Ordner öffnen", "Config laden".
 * Unten rechts führt "Edit" zum Chat-Editor.
 */
public class ConfigMenuScreen extends Screen {
	private static final int LIST_TOP = 34;
	private static final int LIST_LEFT = 20;

	private final List<Path> files = new ArrayList<>();
	private final List<String> dates = new ArrayList<>();

	private int listRight;
	private int listBottom;
	private int rowH;
	private int scroll;
	private String selected;
	private long lastRefresh;

	private String status = "";
	private boolean statusError;
	private long statusUntil;

	public ConfigMenuScreen() {
		super(Lang.c("title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		rowH = this.font.lineHeight + 8;
		listRight = this.width - 20;
		listBottom = this.height - 62;
		reload();

		int y = this.height - 28;
		this.addRenderableWidget(Button.builder(Lang.c("common.open_folder"), b -> FilterConfig.openFolder())
				.bounds(10, y, 140, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("common.load"), b -> loadSelected())
				.bounds(155, y, 110, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("menu.edit"),
						b -> this.minecraft.gui.setScreen(new ChatEditorScreen()))
				.bounds(this.width - 100, y, 90, 20).build());
	}

	private void setStatus(String text, boolean error) {
		status = text;
		statusError = error;
		statusUntil = System.currentTimeMillis() + 6000;
	}

	private void reload() {
		files.clear();
		dates.clear();
		files.addAll(FilterConfig.listExports());
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
		boolean stillThere = false;
		for (Path p : files) {
			String date = "";
			try {
				date = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()).format(fmt);
			} catch (IOException ignored) {
			}
			dates.add(date);
			if (p.getFileName().toString().equals(selected)) {
				stillThere = true;
			}
		}
		if (!stillThere) {
			selected = null;
		}
		int max = Math.max(0, files.size() - visibleRows());
		scroll = Math.min(scroll, max);
		lastRefresh = System.currentTimeMillis();
	}

	private int visibleRows() {
		return Math.max(1, (listBottom - LIST_TOP) / rowH);
	}

	private void loadSelected() {
		Path file = null;
		for (Path p : files) {
			if (p.getFileName().toString().equals(selected)) {
				file = p;
				break;
			}
		}
		if (file == null) {
			setStatus(Lang.t("menu.select_first"), true);
			return;
		}
		String name = file.getFileName().toString();
		int count = FilterConfig.importFrom(file, true);
		if (count < 0) {
			setStatus(Lang.t("common.read_failed", name), true);
		} else {
			setStatus(Lang.t("common.loaded", count, name), false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (mx >= LIST_LEFT && mx <= listRight && my >= LIST_TOP && my <= listBottom) {
			int idx = scroll + (int) ((my - LIST_TOP) / rowH);
			if (idx >= 0 && idx < files.size()) {
				selected = files.get(idx).getFileName().toString();
				if (doubleClick) {
					loadSelected();
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int max = Math.max(0, files.size() - visibleRows());
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// neue Dateien im Ordner automatisch erkennen
		if (System.currentTimeMillis() - lastRefresh > 2000) {
			reload();
		}

		super.extractRenderState(g, mouseX, mouseY, delta);

		g.text(this.font, Lang.t("menu.header"), 20, 10, 0xFFFFFFFF, true);
		g.text(this.font, Lang.t("menu.hint"), 20, 22, 0xFF999999, true);

		g.fill(LIST_LEFT - 2, LIST_TOP - 2, listRight + 2, listBottom + 2, 0xAA000000);

		if (files.isEmpty()) {
			g.text(this.font, Lang.t("menu.empty"),
					LIST_LEFT + 4, LIST_TOP + 4, 0xFFAAAAAA, true);
		}

		String active = FilterConfig.activeConfig;
		for (int i = 0; i < visibleRows() && scroll + i < files.size(); i++) {
			int idx = scroll + i;
			String name = files.get(idx).getFileName().toString();
			int y = LIST_TOP + i * rowH;
			boolean isSelected = name.equals(selected);
			boolean hover = mouseX >= LIST_LEFT && mouseX <= listRight && mouseY >= y && mouseY < y + rowH;
			if (isSelected) {
				g.fill(LIST_LEFT, y, listRight, y + rowH, 0xFF3355AA);
			} else if (hover) {
				g.fill(LIST_LEFT, y, listRight, y + rowH, 0x44FFFFFF);
			}

			int x = LIST_LEFT + 4;
			if (name.equals(active)) {
				g.text(this.font, Lang.t("menu.active"), x, y + 4, 0xFF55FF55, true);
				x += this.font.width(Lang.t("menu.active") + " ");
			}
			String date = dates.get(idx);
			int dateW = this.font.width(date);
			g.text(this.font, trim(name, listRight - x - dateW - 16), x, y + 4, 0xFFFFFFFF, true);
			g.text(this.font, date, listRight - dateW - 4, y + 4, 0xFF999999, true);
		}

		if (System.currentTimeMillis() < statusUntil) {
			g.text(this.font, trim(status, this.width - 40), 20, this.height - 50,
					statusError ? 0xFFFF5555 : 0xFF55FF55, true);
		}
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
}
