package de.heavenswrath;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Heaven's Wrath menu (/killeffect command or the hotkey, default K).
 * Three tabs: Effects, Items, Sounds. Profiles up top, switches along the bottom.
 * Every tile is an invisible button; the menu draws its own picture with an item icon over it.
 */
public class HeavensWrathScreen extends Screen {

	private static final int B = 360;
	private static final int H = 250;

	private static final int GOLD = 0xFFFFC83D;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int DIM = 0xFF777788;
	private static final int GREEN = 0xFF66FF66;
	private static final int RED = 0xFFFF6666;
	private static final int TILE = 0xFF22223A;
	private static final int TILE_HOVER = 0xFF33335C;
	private static final int TILE_SELECTED = 0xFF244A2E;

	/** Requested by the command (page 0 to 2) and opened on the next tick; -1 = nothing. */
	public static int angefordert = -1;

	private static final String[] TABS = { "Effects", "Items", "Sounds" };

	private interface Zeichner {
		void zeichne(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover);
	}

	private record Karte(Button knopf, int x, int y, int w, int h, Zeichner zeichner) {
	}

	private final List<Karte> karten = new ArrayList<>();
	private int seite;
	private int links;
	private int oben;

	public HeavensWrathScreen(int seite) {
		super(Component.literal("Heaven's Wrath"));
		this.seite = Math.max(0, Math.min(TABS.length - 1, seite));
	}

	public static void pruefeOeffnen(Minecraft mc) {
		if (angefordert >= 0) {
			int s = angefordert;
			angefordert = -1;
			mc.setScreen(new HeavensWrathScreen(s));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		karten.clear();
		links = (this.width - B) / 2;
		oben = (this.height - H) / 2;

		// Profiles 1-5
		for (int i = 0; i < HeavensWrathConfig.PROFILE; i++) {
			int n = i;
			karte(links + 52 + i * 30, oben + 22, 26, 16, () -> HeavensWrathConfig.waehleProfil(n),
					(g, x, y, w, h, hover) -> {
						boolean aktuell = HeavensWrathConfig.profil == n;
						rahmen(g, x, y, w, h, aktuell ? GOLD : (hover ? TILE_HOVER : TILE), aktuell ? GOLD : DIM);
						mitte(g, String.valueOf(n + 1), x, y, w, h, aktuell ? 0xFF1A1A1A : WHITE);
					});
		}

		// Tabs
		for (int i = 0; i < TABS.length; i++) {
			int n = i;
			karte(links + 10 + i * 118, oben + 44, 114, 18, () -> {
				seite = n;
				this.rebuildWidgets();
			}, (g, x, y, w, h, hover) -> {
				boolean aktuell = seite == n;
				rahmen(g, x, y, w, h, aktuell ? 0xFF3B2A6E : (hover ? TILE_HOVER : TILE), aktuell ? GOLD : DIM);
				mitte(g, TABS[n], x, y, w, h, aktuell ? GOLD : WHITE);
			});
		}

		switch (seite) {
			case 0 -> baueEffekte();
			case 1 -> baueItems();
			default -> baueSounds();
		}

		// Bottom bar
		int fy = oben + H - 26;
		karte(links + 10, fy, 70, 20, () -> HeavensWrathConfig.aktiv = !HeavensWrathConfig.aktiv, (g, x, y, w, h, hover) -> {
			rahmen(g, x, y, w, h, hover ? TILE_HOVER : TILE, DIM);
			mitte(g, "Effect: " + (HeavensWrathConfig.aktiv ? "ON" : "OFF"), x, y, w, h,
					HeavensWrathConfig.aktiv ? GREEN : RED);
		});
		karte(links + 84, fy, 100, 20, () -> HeavensWrathConfig.koerperAufloesen = !HeavensWrathConfig.koerperAufloesen,
				(g, x, y, w, h, hover) -> {
					rahmen(g, x, y, w, h, hover ? TILE_HOVER : TILE, DIM);
					mitte(g, "Body: " + (HeavensWrathConfig.koerperAufloesen ? "dissolves" : "stands"), x, y, w, h, WHITE);
				});
		karte(links + 188, fy, 110, 20, this::oeffneSkinFenster, (g, x, y, w, h, hover) -> {
			rahmen(g, x, y, w, h, hover ? TILE_HOVER : TILE, DIM);
			String name = HeavensWrathConfig.assistSkin.isEmpty() ? "you" : HeavensWrathConfig.assistSkin;
			mitte(g, kuerze("Assist skin: " + name, w - 6), x, y, w, h, WHITE);
		});
		karte(links + 302, fy, 48, 20, this::onClose, (g, x, y, w, h, hover) -> {
			rahmen(g, x, y, w, h, hover ? 0xFF3A6A3A : 0xFF2A4A2A, GREEN);
			mitte(g, "Done", x, y, w, h, WHITE);
		});
	}

	/** Tab 1: kill effects (4 columns, same grid as the Items and Sounds tabs). */
	private void baueEffekte() {
		int top = oben + 68;
		for (int i = 0; i < HeavensWrathConfig.MODI.size(); i++) {
			String m = HeavensWrathConfig.MODI.get(i);
			int x = links + 10 + (i % 4) * 86;
			int y = top + (i / 4) * 45;
			karte(x, y, 82, 40, () -> HeavensWrathConfig.modus = m, (g, bx, by, w, h, hover) -> {
				boolean gewaehlt = HeavensWrathConfig.modus.equals(m);
				rahmen(g, bx, by, w, h, gewaehlt ? TILE_SELECTED : hover ? TILE_HOVER : TILE, gewaehlt ? GREEN : DIM);
				HeavensWrathGfx.item(g, new ItemStack(HeavensWrathConfig.icon(m)), bx + w / 2 - 8, by + 4);
				mitte(g, kuerze(HeavensWrathConfig.displayName(m), w - 4), bx, by + 22, w, 14, gewaehlt ? GREEN : WHITE);
			});
		}
	}

	/** Tab 2: the item that falls (4 columns). */
	private void baueItems() {
		List<String> namen = new ArrayList<>();
		namen.add("weapon");
		namen.addAll(HeavensWrathConfig.ITEMS.keySet());
		int top = oben + 68;
		for (int i = 0; i < namen.size(); i++) {
			String n = namen.get(i);
			int x = links + 10 + (i % 4) * 86;
			int y = top + (i / 4) * 45;
			Item item = "weapon".equals(n) ? Items.DIAMOND_SWORD : HeavensWrathConfig.ITEMS.get(n);
			karte(x, y, 82, 40, () -> HeavensWrathConfig.item = n, (g, bx, by, w, h, hover) -> {
				boolean gewaehlt = HeavensWrathConfig.item.equals(n);
				rahmen(g, bx, by, w, h, gewaehlt ? TILE_SELECTED : hover ? TILE_HOVER : TILE, gewaehlt ? GREEN : DIM);
				HeavensWrathGfx.item(g, new ItemStack(item), bx + w / 2 - 8, by + 4);
				mitte(g, kuerze(HeavensWrathConfig.label(n), w - 4), bx, by + 22, w, 14, gewaehlt ? GREEN : WHITE);
			});
		}
	}

	/** Tab 3: impact sound (4 columns, click plays it as a preview). */
	private void baueSounds() {
		List<String> namen = HeavensWrathConfig.soundNamen();
		int top = oben + 68;
		for (int i = 0; i < namen.size(); i++) {
			String n = namen.get(i);
			int x = links + 10 + (i % 4) * 86;
			int y = top + (i / 4) * 45;
			karte(x, y, 82, 40, () -> {
				HeavensWrathConfig.sound = n;
				HeavensWrathConfig.vorschau();
			}, (g, bx, by, w, h, hover) -> {
				boolean gewaehlt = HeavensWrathConfig.sound.equals(n);
				rahmen(g, bx, by, w, h, gewaehlt ? TILE_SELECTED : hover ? TILE_HOVER : TILE, gewaehlt ? GREEN : DIM);
				HeavensWrathGfx.item(g, new ItemStack("off".equals(n) ? Items.BARRIER : Items.NOTE_BLOCK), bx + w / 2 - 8,
						by + 4);
				mitte(g, kuerze(HeavensWrathConfig.label(n), w - 4), bx, by + 22, w, 14, gewaehlt ? GREEN : WHITE);
			});
		}
	}

	private void karte(int x, int y, int w, int h, Runnable klick, Zeichner zeichner) {
		Button knopf = Button.builder(Component.empty(), b -> {
			klick.run();
			HeavensWrathConfig.speichern();
		}).bounds(x, y, w, h).build();
		this.addRenderableWidget(knopf);
		karten.add(new Karte(knopf, x, y, w, h, zeichner));
	}

	/** Opens the small popup where you type the assist player's name. */
	private void oeffneSkinFenster() {
		this.minecraft.setScreen(new HeavensWrathSkinScreen(this));
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		HeavensWrathGfx.flaeche(g, links - 2, oben - 2, links + B + 2, oben + H + 2, GOLD);
		HeavensWrathGfx.flaeche(g, links, oben, links + B, oben + H, 0xF0141420);
		HeavensWrathGfx.flaeche(g, links, oben, links + B, oben + 18, 0xFF2A1B4D);
		HeavensWrathGfx.flaeche(g, links, oben + 18, links + B, oben + 19, GOLD);

		String titel = "HEAVEN'S WRATH";
		HeavensWrathGfx.text(g, this.font, titel, links + (B - this.font.width(titel)) / 2, oben + 5, GOLD);
		HeavensWrathGfx.text(g, this.font, "Profile", links + 10, oben + 26, GRAY);
		HeavensWrathGfx.text(g, this.font, kuerze(HeavensWrathConfig.profilKurz(HeavensWrathConfig.profil), 134),
				links + 210, oben + 26, DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		for (Karte k : karten) {
			k.zeichner().zeichne(g, k.x(), k.y(), k.w(), k.h(), k.knopf().isHovered());
		}
	}

	// ------------------------------------------------------------------
	// Small helpers
	// ------------------------------------------------------------------

	private static void rahmen(GuiGraphicsExtractor g, int x, int y, int w, int h, int fuellung, int rand) {
		HeavensWrathGfx.flaeche(g, x, y, x + w, y + h, rand);
		HeavensWrathGfx.flaeche(g, x + 1, y + 1, x + w - 1, y + h - 1, fuellung);
	}

	/** Text centered horizontally and vertically inside the given box. */
	private void mitte(GuiGraphicsExtractor g, String text, int x, int y, int w, int h, int farbe) {
		HeavensWrathGfx.text(g, this.font, text, x + (w - this.font.width(text)) / 2, y + (h - 8) / 2 + 1, farbe);
	}

	private String kuerze(String text, int max) {
		if (this.font.width(text) <= max) {
			return text;
		}
		String s = text;
		while (s.length() > 1 && this.font.width(s + "..") > max) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "..";
	}
}
