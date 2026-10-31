package dev.chatfilter;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;

public class ChatFilterClient implements ClientModInitializer {
	public static final String MOD_ID = "chatfilter";
	private static final int MAX_LOG = 300;

	/** Die letzten Chatzeilen (nach dem Filtern), die der Editor anzeigt. */
	public static final Deque<String> LOG = new ArrayDeque<>();

	private static KeyMapping openEditorKey;

	@Override
	public void onInitializeClient() {
		FilterConfig.load();
		FilterConfig.installDefaults();

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		openEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.chatfilter.open_editor",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_G,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openEditorKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new ConfigMenuScreen());
				}
			}
		});

		// 1) Nur-Text-Regeln: Teile aus der Nachricht herausschneiden
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
				overlay ? message : FilterConfig.applyRemovals(message));

		// 2) Ganze-Nachricht-Regeln: Nachricht verwerfen, sonst im Editor-Log merken
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
				overlay || accept(message));

		// Signierte Spieler-Chatnachrichten: es gibt kein MODIFY_CHAT. Daher hier die Nachricht
		// (mit Namens-Präfix) prüfen und bei Nur-Text-Regeln die bereinigte Fassung selbst anzeigen.
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, timestamp) -> {
			Component full = params.decorate(message);
			if (!accept(full)) {
				return false;
			}
			Component cleaned = FilterConfig.applyRemovals(full);
			Minecraft client = Minecraft.getInstance();
			if (cleaned != full && client.player != null) {
				client.player.sendSystemMessage(cleaned);
				return false;
			}
			return true;
		});
	}

	private static boolean accept(Component message) {
		String plain = message.getString();
		if (FilterConfig.blockEmpty && FilterConfig.isEmptyLike(plain)) {
			return false;
		}
		if (FilterConfig.shouldHide(plain)) {
			return false;
		}
		Component cleaned = FilterConfig.applyRemovals(message);
		String cleanedPlain = cleaned.getString();
		if (!plain.isBlank() && cleanedPlain.isBlank()) {
			return false;
		}
		for (String line : cleanedPlain.split("\\R")) {
			if (!line.isBlank()) {
				LOG.addLast(line);
				while (LOG.size() > MAX_LOG) {
					LOG.removeFirst();
				}
			}
		}
		return true;
	}
}
