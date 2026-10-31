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

/** Liste aller Config-Dateien im Ordner chatfilter-configs. Klick auf eine Datei lädt sie. */
public class ConfigPickerScreen extends Screen {
	private static final int LIST_TOP = 34;
	private static final int LIST_LEFT = 20;

	private final ChatEditorScreen parent;
	private final List<Path> files = new ArrayList<>();
	private final List<String> dates = new ArrayList<>();

	private int listRight;
	private int listBottom;
	private int rowH;
	private int scroll;
	private boolean replace = true;

	public ConfigPickerScreen(ChatEditorScreen parent) {
		super(Lang.c("common.load"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rowH = this.font.lineHeight + 8;
		listRight = this.width - 20;
		listBottom = this.height - 42;
		reload();

		int y = this.height - 28;
		this.addRenderableWidget(Button.builder(Lang.c("common.open_folder"), b -> FilterConfig.openFolder())
				.bounds(10, y, 120, 20).build());

		this.addRenderableWidget(Button.builder(replaceLabel(), b -> {
			replace = !replace;
			b.setMessage(replaceLabel());
		}).bounds(135, y, 160, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("picker.reload"), b -> reload())
				.bounds(300, y, 110, 20).build());

		this.addRenderableWidget(Button.builder(Lang.c("picker.back"), b -> this.onClose())
				.bounds(415, y, 70, 20).build());
	}

	private Component replaceLabel() {
		return Lang.c(replace ? "picker.replace" : "picker.add");
	}

	private void reload() {
		files.clear();
		dates.clear();
		files.addAll(FilterConfig.listExports());
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
		for (Path p : files) {
			String date = "";
			try {
				LocalDateTime time = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault());
				date = time.format(fmt);
			} catch (IOException ignored) {
			}
			dates.add(date);
		}
		scroll = 0;
	}

	private int visibleRows() {
		return Math.max(1, (listBottom - LIST_TOP) / rowH);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (mx >= LIST_LEFT && mx <= listRight && my >= LIST_TOP && my <= listBottom) {
			int idx = scroll + (int) ((my - LIST_TOP) / rowH);
			if (idx >= 0 && idx < files.size()) {
				Path file = files.get(idx);
				String name = file.getFileName().toString();
				int count = FilterConfig.importFrom(file, replace);
				parent.setStatus(count < 0
						? Lang.t("common.read_failed", name)
						: Lang.t("common.loaded", count, name));
				this.minecraft.gui.setScreen(parent);
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
		super.extractRenderState(g, mouseX, mouseY, delta);

		g.text(this.font, Lang.t("picker.header"), 20, 10, 0xFFFFFFFF, true);
		String folder = FilterConfig.folder().toAbsolutePath().toString();
		g.text(this.font, Lang.t("picker.folder", "") + trim(folder, this.width - 80), 20, 22, 0xFF999999, true);

		g.fill(LIST_LEFT - 2, LIST_TOP - 2, listRight + 2, listBottom + 2, 0xAA000000);

		if (files.isEmpty()) {
			g.text(this.font, Lang.t("picker.empty"),
					LIST_LEFT + 4, LIST_TOP + 4, 0xFFAAAAAA, true);
			return;
		}

		for (int i = 0; i < visibleRows() && scroll + i < files.size(); i++) {
			int idx = scroll + i;
			int y = LIST_TOP + i * rowH;
			boolean hover = mouseX >= LIST_LEFT && mouseX <= listRight && mouseY >= y && mouseY < y + rowH;
			if (hover) {
				g.fill(LIST_LEFT, y, listRight, y + rowH, 0x55FFFFFF);
			}
			String date = dates.get(idx);
			int dateW = this.font.width(date);
			g.text(this.font, trim(files.get(idx).getFileName().toString(), listRight - LIST_LEFT - dateW - 24),
					LIST_LEFT + 4, y + 4, hover ? 0xFFFFFFAA : 0xFFFFFFFF, true);
			g.text(this.font, date, listRight - dateW - 4, y + 4, 0xFF999999, true);
		}
	}

	private String trim(String s, int maxW) {
		if (this.font.width(s) <= maxW) {
			return s;
		}
		while (!s.isEmpty() && this.font.width("..." + s) > maxW) {
			s = s.substring(1);
		}
		return "..." + s;
	}
}
