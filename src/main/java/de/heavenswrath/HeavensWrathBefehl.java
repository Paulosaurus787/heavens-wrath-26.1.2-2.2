package de.heavenswrath;

import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** /killeffect and /killsound - chat commands; the easier way is the menu (Taste K, or just /killeffect). */
public final class HeavensWrathBefehl {

	private HeavensWrathBefehl() {
	}

	public static void registrieren(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> wurzel = ClientCommands.literal("killeffect")
				.executes(ctx -> {
					HeavensWrathScreen.angefordert = 0;
					return 1;
				});

		wurzel.then(ClientCommands.literal("on").executes(ctx -> {
			HeavensWrathConfig.aktiv = true;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));
		wurzel.then(ClientCommands.literal("off").executes(ctx -> {
			HeavensWrathConfig.aktiv = false;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));

		LiteralArgumentBuilder<FabricClientCommandSource> item = ClientCommands.literal("item");
		item.then(ClientCommands.literal("weapon").executes(ctx -> {
			setzeItem("weapon");
			menue(ctx.getSource());
			return 1;
		}));
		for (String name : HeavensWrathConfig.ITEMS.keySet()) {
			item.then(ClientCommands.literal(name).executes(ctx -> {
				setzeItem(name);
				menue(ctx.getSource());
				return 1;
			}));
		}
		wurzel.then(item);

		LiteralArgumentBuilder<FabricClientCommandSource> body = ClientCommands.literal("body");
		body.then(ClientCommands.literal("stand").executes(ctx -> {
			HeavensWrathConfig.koerperAufloesen = false;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));
		body.then(ClientCommands.literal("dissolve").executes(ctx -> {
			HeavensWrathConfig.koerperAufloesen = true;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));
		wurzel.then(body);

		LiteralArgumentBuilder<FabricClientCommandSource> modus = ClientCommands.literal("mode");
		for (String m : HeavensWrathConfig.MODI) {
			modus.then(ClientCommands.literal(m).executes(ctx -> {
				HeavensWrathConfig.modus = m;
				HeavensWrathConfig.speichern();
				menue(ctx.getSource());
				return 1;
			}));
		}
		wurzel.then(modus);

		LiteralArgumentBuilder<FabricClientCommandSource> profil = ClientCommands.literal("profile");
		for (int i = 1; i <= HeavensWrathConfig.PROFILE; i++) {
			int nummer = i - 1;
			profil.then(ClientCommands.literal(String.valueOf(i)).executes(ctx -> {
				HeavensWrathConfig.waehleProfil(nummer);
				menue(ctx.getSource());
				return 1;
			}));
		}
		wurzel.then(profil);

		dispatcher.register(wurzel);
		registriereSound(dispatcher);
	}

	/** /killsound - switches the impact sound (clicking a choice plays it as a preview). */
	private static void registriereSound(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> wurzel = ClientCommands.literal("killsound")
				.executes(ctx -> {
					HeavensWrathScreen.angefordert = 2;
					return 1;
				});
		for (String name : HeavensWrathConfig.soundNamen()) {
			wurzel.then(ClientCommands.literal(name).executes(ctx -> {
				HeavensWrathConfig.sound = name;
				HeavensWrathConfig.speichern();
				HeavensWrathConfig.vorschau();
				soundMenue(ctx.getSource());
				return 1;
			}));
		}
		dispatcher.register(wurzel);
	}

	private static void soundMenue(FabricClientCommandSource quelle) {
		quelle.sendFeedback(Component.literal("===== Heaven's Wrath: Sound =====").withStyle(ChatFormatting.GOLD));
		quelle.sendFeedback(zeile("Sound: ", HeavensWrathConfig.label(HeavensWrathConfig.sound)));
		MutableComponent knoepfe = Component.literal("  ");
		for (String name : HeavensWrathConfig.soundNamen()) {
			knoepfe.append(knopf(HeavensWrathConfig.label(name), "/killsound " + name, HeavensWrathConfig.sound.equals(name)));
		}
		quelle.sendFeedback(knoepfe);
		quelle.sendFeedback(Component.literal("Click a sound to select it and hear a preview.").withStyle(ChatFormatting.GRAY));
	}

	private static void setzeItem(String name) {
		HeavensWrathConfig.item = name;
		HeavensWrathConfig.speichern();
	}

	private static void menue(FabricClientCommandSource quelle) {
		quelle.sendFeedback(Component.literal("===== Heaven's Wrath =====  Profile " + (HeavensWrathConfig.profil + 1))
				.withStyle(ChatFormatting.GOLD));

		quelle.sendFeedback(zeile("Effect: ", HeavensWrathConfig.aktiv ? "ON" : "OFF")
				.append(knopf("on", "/killeffect on", HeavensWrathConfig.aktiv))
				.append(knopf("off", "/killeffect off", !HeavensWrathConfig.aktiv)));

		quelle.sendFeedback(zeile("Body: ", HeavensWrathConfig.koerperAufloesen ? "dissolves" : "stays standing")
				.append(knopf("stand", "/killeffect body stand", !HeavensWrathConfig.koerperAufloesen))
				.append(knopf("dissolve", "/killeffect body dissolve", HeavensWrathConfig.koerperAufloesen)));

		quelle.sendFeedback(zeile("Item: ", HeavensWrathConfig.label(HeavensWrathConfig.item)));
		MutableComponent items = Component.literal("  ");
		items.append(knopf("your weapon", "/killeffect item weapon", HeavensWrathConfig.item.equals("weapon")));
		for (Map.Entry<String, ?> e : HeavensWrathConfig.ITEMS.entrySet()) {
			String name = e.getKey();
			items.append(knopf(HeavensWrathConfig.label(name), "/killeffect item " + name, HeavensWrathConfig.item.equals(name)));
		}
		quelle.sendFeedback(items);

		MutableComponent modi = zeile("Kill effect: ", HeavensWrathConfig.displayName(HeavensWrathConfig.modus));
		for (String m : HeavensWrathConfig.MODI) {
			modi.append(knopf(HeavensWrathConfig.displayName(m), "/killeffect mode " + m, HeavensWrathConfig.modus.equals(m)));
		}
		quelle.sendFeedback(modi);

		quelle.sendFeedback(Component.literal("Tip: open the Heaven's Wrath window with the hotkey (default K) for a nicer menu.")
				.withStyle(ChatFormatting.GRAY));
	}

	private static MutableComponent zeile(String titel, String wert) {
		return Component.literal(titel).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(wert).withStyle(ChatFormatting.WHITE));
	}

	private static MutableComponent knopf(String text, String befehl, boolean gewaehlt) {
		return Component.literal(" [" + text + "]").withStyle(stil -> stil
				.withColor(gewaehlt ? ChatFormatting.GREEN : ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.RunCommand(befehl))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(befehl))));
	}
}
