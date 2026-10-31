package dev.chatfilter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hält die Regeln, speichert sie in config/chatfilter.json und wendet sie auf Nachrichten an. */
public final class FilterConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chatfilter.json");
	private static final Pattern TOKEN = Pattern.compile("\\{\\*\\*?\\}");

	public static final String VAR_WORD = "{*}";
	public static final String VAR_ANY = "{**}";

	public static final List<FilterRule> RULES = new CopyOnWriteArrayList<>();

	private FilterConfig() {
	}

	public static void load() {
		loadSettings();
		try {
			if (!Files.exists(FILE)) {
				return;
			}
			try (Reader reader = Files.newBufferedReader(FILE)) {
				FilterRule[] loaded = GSON.fromJson(reader, FilterRule[].class);
				RULES.clear();
				if (loaded != null) {
					RULES.addAll(Arrays.asList(loaded));
				}
			}
		} catch (Exception e) {
			System.err.println("[chatfilter] Konnte Config nicht laden: " + e);
		}
	}

	public static void save() {
		try {
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(RULES.toArray(new FilterRule[0]), writer);
			}
		} catch (Exception e) {
			System.err.println("[chatfilter] Konnte Config nicht speichern: " + e);
		}
	}

	// ---------- Einstellungen / leere Zeilen ----------

	private static final Path SETTINGS_FILE = FabricLoader.getInstance().getConfigDir().resolve("chatfilter-settings.json");

	private static class Settings {
		boolean blockEmpty = true;
		List<String> installedDefaults = new ArrayList<>();
		String activeConfig = "";
	}

	/** Name der zuletzt geladenen Config-Datei (für die Anzeige "aktiv"). */
	public static volatile String activeConfig = "";

	/** Namen der mitgelieferten Configs, die schon einmal in den Ordner kopiert wurden. */
	private static final List<String> installedDefaults = new ArrayList<>();

	/** Mitgelieferte Configs (liegen in der .jar unter assets/chatfilter/default-configs/). */
	private static final String[] DEFAULT_CONFIGS = {"Colorgens-Spamfilter.json", "Colorgens-msg-only.json"};

	/** Leere Chatzeilen automatisch blocken (Standard: an). */
	public static volatile boolean blockEmpty = true;

	private static void loadSettings() {
		try {
			if (!Files.exists(SETTINGS_FILE)) {
				return;
			}
			try (Reader reader = Files.newBufferedReader(SETTINGS_FILE)) {
				Settings s = GSON.fromJson(reader, Settings.class);
				if (s != null) {
					blockEmpty = s.blockEmpty;
					activeConfig = s.activeConfig == null ? "" : s.activeConfig;
					installedDefaults.clear();
					if (s.installedDefaults != null) {
						installedDefaults.addAll(s.installedDefaults);
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[chatfilter] Konnte Einstellungen nicht laden: " + e);
		}
	}

	public static void saveSettings() {
		try {
			Settings s = new Settings();
			s.blockEmpty = blockEmpty;
			s.installedDefaults = new ArrayList<>(installedDefaults);
			s.activeConfig = activeConfig;
			try (Writer writer = Files.newBufferedWriter(SETTINGS_FILE)) {
				GSON.toJson(s, writer);
			}
		} catch (Exception e) {
			System.err.println("[chatfilter] Konnte Einstellungen nicht speichern: " + e);
		}
	}

	/**
	 * Kopiert die mitgelieferten Configs beim Start in den Ordner chatfilter-configs.
	 * Fehlt eine mitgelieferte Datei im Ordner, wird sie bei jedem Start neu angelegt;
	 * vorhandene Dateien werden nie überschrieben.
	 */
	public static void installDefaults() {
		Path dir = folder();
		boolean changed = false;
		for (String name : DEFAULT_CONFIGS) {
			Path target = dir.resolve(name);
			if (!Files.exists(target)) {
				try (InputStream in = FilterConfig.class.getResourceAsStream("/assets/chatfilter/default-configs/" + name)) {
					if (in == null) {
						continue;
					}
					Files.copy(in, target);
				} catch (IOException e) {
					System.err.println("[chatfilter] Konnte Standard-Config nicht kopieren: " + e);
					continue;
				}
			}
			if (installedDefaults.add(name)) {
				changed = true;
			}
		}
		if (changed) {
			saveSettings();
		}
	}

	/**
	 * Steht in der Zeile effektiv nichts? Zählt auch Leerzeichen, Steuer- und unsichtbare Zeichen
	 * (z.B. Zero-Width-Space, geschütztes Leerzeichen, Hangul-Filler, Braille-Leerzeichen).
	 */
	public static boolean isEmptyLike(String text) {
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == 0x3164 || cp == 0x115F || cp == 0x1160 || cp == 0x2800) {
				continue;
			}
			int type = Character.getType(cp);
			boolean invisible = Character.isWhitespace(cp)
					|| type == Character.SPACE_SEPARATOR
					|| type == Character.LINE_SEPARATOR
					|| type == Character.PARAGRAPH_SEPARATOR
					|| type == Character.CONTROL
					|| type == Character.FORMAT;
			if (!invisible) {
				return false;
			}
		}
		return true;
	}

	// ---------- Export / Import ----------

	/** Ordner für gespeicherte Configs: .minecraft/chatfilter-configs (wird bei Bedarf angelegt). */
	public static Path folder() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("chatfilter-configs");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			System.err.println("[chatfilter] Konnte Ordner nicht anlegen: " + e);
		}
		return dir;
	}

	/** Schreibt die aktuellen Regeln in eine neue Datei mit Zeitstempel und gibt deren Pfad zurück. */
	public static Path exportNew() throws IOException {
		String name = "chatfilter-"
				+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".json";
		Path target = folder().resolve(name);
		try (Writer writer = Files.newBufferedWriter(target)) {
			GSON.toJson(RULES.toArray(new FilterRule[0]), writer);
		}
		return target;
	}

	/** Alle .json-Dateien im Config-Ordner, neueste zuerst. */
	public static List<Path> listExports() {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> stream = Files.list(folder())) {
			stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json")).forEach(out::add);
		} catch (IOException e) {
			System.err.println("[chatfilter] Konnte Ordner nicht lesen: " + e);
		}
		out.sort(Comparator.comparingLong((Path p) -> {
			try {
				return Files.getLastModifiedTime(p).toMillis();
			} catch (IOException e) {
				return 0L;
			}
		}).reversed());
		return out;
	}

	/**
	 * Lädt Regeln aus einer Datei.
	 *
	 * @param replace true = bestehende Regeln ersetzen, false = neue Regeln hinzufügen (ohne Duplikate)
	 * @return Anzahl gelesener Regeln, oder -1 bei einem Fehler
	 */
	public static int importFrom(Path file, boolean replace) {
		try (Reader reader = Files.newBufferedReader(file)) {
			FilterRule[] loaded = GSON.fromJson(reader, FilterRule[].class);
			if (loaded == null) {
				return -1;
			}
			List<FilterRule> valid = new ArrayList<>();
			for (FilterRule rule : loaded) {
				if (rule != null && rule.text != null && !rule.text.isBlank()) {
					valid.add(rule);
				}
			}
			if (replace) {
				RULES.clear();
			}
			for (FilterRule rule : valid) {
				boolean duplicate = false;
				for (FilterRule existing : RULES) {
					if (existing.text.equalsIgnoreCase(rule.text)) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate) {
					RULES.add(rule);
				}
			}
			save();
			if (replace) {
				activeConfig = file.getFileName().toString();
				saveSettings();
			}
			return valid.size();
		} catch (Exception e) {
			System.err.println("[chatfilter] Konnte Config nicht importieren: " + e);
			return -1;
		}
	}

	/** Öffnet den Config-Ordner im Datei-Explorer des Betriebssystems. */
	public static void openFolder() {
		String dir = folder().toAbsolutePath().toString();
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			ProcessBuilder pb;
			if (os.contains("win")) {
				pb = new ProcessBuilder("explorer.exe", dir);
			} else if (os.contains("mac")) {
				pb = new ProcessBuilder("open", dir);
			} else {
				pb = new ProcessBuilder("xdg-open", dir);
			}
			pb.start();
		} catch (Exception e) {
			System.err.println("[chatfilter] Konnte Ordner nicht öffnen: " + e);
		}
	}

	/** Text mit Platzhaltern lesbar machen, z.B. für die Anzeige im Editor. */
	public static String display(String pattern) {
		return pattern.replace(VAR_ANY, "[*]").replace(VAR_WORD, "[*]");
	}

	/** Besteht das Muster nur aus Platzhaltern/Leerraum? Dann würde es alles treffen. */
	public static boolean isOnlyVariables(String pattern) {
		return pattern.replace(VAR_ANY, "").replace(VAR_WORD, "").isBlank();
	}

	private static Pattern compile(FilterRule rule) {
		Pattern p = rule.compiled;
		if (p != null) {
			return p;
		}
		String text = rule.text == null ? "" : rule.text;
		StringBuilder regex = new StringBuilder();
		Matcher m = TOKEN.matcher(text);
		int last = 0;
		while (m.find()) {
			regex.append(Pattern.quote(text.substring(last, m.start())));
			if (m.group().equals(VAR_ANY)) {
				regex.append(m.end() == text.length() ? ".+" : ".+?");
			} else {
				regex.append("\\S+");
			}
			last = m.end();
		}
		regex.append(Pattern.quote(text.substring(last)));
		p = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		rule.compiled = p;
		return p;
	}

	private static boolean usable(FilterRule rule) {
		return rule.text != null && !rule.text.isEmpty() && !isOnlyVariables(rule.text);
	}

	/** Soll die ganze Nachricht unterdrückt werden? */
	public static boolean shouldHide(String plain) {
		for (FilterRule rule : RULES) {
			if (!rule.removeOnlyText && usable(rule) && compile(rule).matcher(plain).find()) {
				return true;
			}
		}
		return false;
	}

	/** Trifft irgendeine Regel auf diesen Text zu? (nur für die Anzeige im Editor) */
	public static boolean isAffected(String plain) {
		for (FilterRule rule : RULES) {
			if (usable(rule) && compile(rule).matcher(plain).find()) {
				return true;
			}
		}
		return false;
	}

	/** Schneidet alle "nur Text entfernen"-Treffer aus der Nachricht und behält Farben/Styles bei. */
	public static Component applyRemovals(Component message) {
		List<FilterRule> removal = new ArrayList<>();
		for (FilterRule rule : RULES) {
			if (rule.removeOnlyText && usable(rule)) {
				removal.add(rule);
			}
		}
		if (removal.isEmpty()) {
			return message;
		}

		String plain = message.getString();
		boolean[] cut = new boolean[plain.length()];
		boolean any = false;
		for (FilterRule rule : removal) {
			Matcher m = compile(rule).matcher(plain);
			while (m.find()) {
				if (m.end() > m.start()) {
					Arrays.fill(cut, m.start(), m.end(), true);
					any = true;
				}
			}
		}
		if (!any) {
			return message;
		}

		MutableComponent result = Component.empty();
		int[] pos = {0};
		message.visit((style, text) -> {
			StringBuilder kept = new StringBuilder();
			for (int k = 0; k < text.length(); k++) {
				if (pos[0] + k < cut.length && !cut[pos[0] + k]) {
					kept.append(text.charAt(k));
				}
			}
			pos[0] += text.length();
			if (kept.length() > 0) {
				result.append(Component.literal(kept.toString()).withStyle(style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return result;
	}
}
