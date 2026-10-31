package dev.chatfilter;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Übersetzungs-Helfer: alle Texte stehen in assets/chatfilter/lang/*.json (Schlüssel "chatfilter.*"). */
final class Lang {
	private Lang() {
	}

	static MutableComponent c(String key, Object... args) {
		return Component.translatable("chatfilter." + key, args);
	}

	static String t(String key, Object... args) {
		return c(key, args).getString();
	}
}
