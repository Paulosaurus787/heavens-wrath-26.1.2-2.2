package de.heavenswrath;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small popup where you type the name of the player whose skin the assist kill effect should use. */
public class HeavensWrathSkinScreen extends Screen {

	private static final int B = 240;
	private static final int H = 200;
	private static final int GOLD = 0xFFFFC83D;
	private static final int GRAY = 0xFFAAAAAA;

	private final HeavensWrathScreen zurueck;
	private EditBox feld;
	private int links;
	private int oben;

	public HeavensWrathSkinScreen(HeavensWrathScreen zurueck) {
		super(Component.literal("Assist Skin"));
		this.zurueck = zurueck;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		links = (this.width - B) / 2;
		oben = (this.height - H) / 2;

		feld = new EditBox(this.font, links + 10, oben + 40, B - 20, 20, Component.literal("Player name"));
		feld.setMaxLength(16);
		feld.setValue(HeavensWrathConfig.assistSkin);
		this.addRenderableWidget(feld);
		this.setInitialFocus(feld);

		// Online players are a shortcut; you can also just type any name by hand.
		List<String> online = HeavensWrathConfig.onlineNamen();
		int zeile = oben + 68;
		int spalte = 0;
		for (String name : online) {
			if (zeile > oben + H - 64) {
				break; // the popup stays small; more names simply don't fit
			}
			int x = links + 10 + spalte * ((B - 20) / 2 + 4);
			Button knopf = Button.builder(Component.literal(name), b -> feld.setValue(name))
					.bounds(x, zeile, (B - 20) / 2 - 2, 16).build();
			this.addRenderableWidget(knopf);
			spalte++;
			if (spalte >= 2) {
				spalte = 0;
				zeile += 18;
			}
		}

		this.addRenderableWidget(Button.builder(Component.literal("Use my own skin"), b -> feld.setValue(""))
				.bounds(links + 10, oben + H - 54, B - 20, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> speichern())
				.bounds(links + 10, oben + H - 26, (B - 20) / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
				.bounds(links + (B - 20) / 2 + 14, oben + H - 26, (B - 20) / 2 - 2, 20).build());
	}

	/** Only letters, numbers and underscore (a valid Minecraft name); empty means "use my own skin". */
	private void speichern() {
		String wert = feld.getValue().trim();
		if (wert.matches("[A-Za-z0-9_]{0,16}")) {
			HeavensWrathConfig.assistSkin = wert;
			HeavensWrathConfig.speichern();
		}
		this.onClose();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(zurueck);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		HeavensWrathGfx.flaeche(g, links - 2, oben - 2, links + B + 2, oben + H + 2, GOLD);
		HeavensWrathGfx.flaeche(g, links, oben, links + B, oben + H, 0xF0141420);
		HeavensWrathGfx.text(g, this.font, "Assist Player Skin", links + 10, oben + 8, GOLD);
		HeavensWrathGfx.text(g, this.font, "Type a name, or click a player below.", links + 10, oben + 24, GRAY);
	}
}
