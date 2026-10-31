package de.heavenswrath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mod settings (saved to config/heavens_wrath.properties). */
public final class HeavensWrathConfig {

	/** Choices for /killeffect item ...  (id -> item). */
	public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
	static {
		ITEMS.put("netherite_sword", Items.NETHERITE_SWORD);
		ITEMS.put("diamond_sword", Items.DIAMOND_SWORD);
		ITEMS.put("iron_sword", Items.IRON_SWORD);
		ITEMS.put("golden_sword", Items.GOLDEN_SWORD);
		ITEMS.put("netherite_axe", Items.NETHERITE_AXE);
		ITEMS.put("netherite_pickaxe", Items.NETHERITE_PICKAXE);
		ITEMS.put("trident", Items.TRIDENT);
		ITEMS.put("mace", Items.MACE);
		ITEMS.put("bow", Items.BOW);
		ITEMS.put("anvil", Items.ANVIL);
	}

	/** Choices for /killsound ...  (id -> sound). Plus "combo" and "off". */
	public static final Map<String, SoundEvent> SOUNDS = new LinkedHashMap<>();
	static {
		SOUNDS.put("wither", SoundEvents.WITHER_SPAWN);
		SOUNDS.put("wither_death", SoundEvents.WITHER_DEATH);
		SOUNDS.put("thunder", SoundEvents.LIGHTNING_BOLT_THUNDER);
		SOUNDS.put("lightning_impact", SoundEvents.LIGHTNING_BOLT_IMPACT);
		SOUNDS.put("dragon", SoundEvents.ENDER_DRAGON_GROWL);
		SOUNDS.put("dragon_death", SoundEvents.ENDER_DRAGON_DEATH);
		SOUNDS.put("anvil", SoundEvents.ANVIL_LAND);
		SOUNDS.put("warden", SoundEvents.WARDEN_SONIC_BOOM);
		SOUNDS.put("trident", sound(SoundEvents.TRIDENT_THUNDER));
	}

	/** Every kill effect for /killeffect mode ... All of them are always free to use. */
	public static final List<String> MODI = List.of("weapon", "goat", "llama", "assist", "warden", "golem",
			"lightning", "meteor", "angel", "slime", "tsundere");

	/** English display name for each kill effect, shown in the menu and in chat. */
	public static String displayName(String modus) {
		return switch (modus) {
			case "weapon" -> "Falling Weapon";
			case "goat" -> "Goat Ram";
			case "llama" -> "Llama Spit";
			case "assist" -> "Assist Kill";
			case "warden" -> "Sonic Boom";
			case "golem" -> "Golem Moon Punch";
			case "lightning" -> "Lightning Strike";
			case "meteor" -> "Meteor";
			case "angel" -> "Ascension";
			case "slime" -> "Slime Curse";
			case "tsundere" -> "Tsundere";
			default -> modus;
		};
	}

	/** Icon shown for each kill effect in the menu. */
	public static Item icon(String modus) {
		return switch (modus) {
			case "goat" -> Items.GOAT_HORN;
			case "llama" -> Items.LEAD;
			case "assist" -> Items.MACE;
			case "warden" -> Items.ECHO_SHARD;
			case "golem" -> Items.IRON_BLOCK;
			case "lightning" -> Items.LIGHTNING_ROD;
			case "meteor" -> Items.MAGMA_BLOCK;
			case "angel" -> Items.TOTEM_OF_UNDYING;
			case "slime" -> Items.SLIME_BALL;
			case "tsundere" -> Items.SHIELD;
			default -> Items.DIAMOND_SWORD;
		};
	}

	/** Number of profiles. */
	public static final int PROFILE = 5;

	/** A profile stores every choice setting. */
	private static final class Profil {
		String item = "weapon";
		String sound = "wither";
		String modus = "weapon";
		String assistSkin = "";
		boolean koerperAufloesen = false;
	}

	private static final Profil[] PROFIL_DATEN = new Profil[PROFILE];
	static {
		for (int i = 0; i < PROFILE; i++) {
			PROFIL_DATEN[i] = new Profil();
		}
	}

	/** Effect on/off (applies to every profile). */
	public static boolean aktiv = true;
	/** Which profile is active (0 to PROFILE-1). */
	public static int profil = 0;

	// ---- Values of the CURRENT profile ----
	/** "weapon" = the item you killed with; otherwise a key from ITEMS. */
	public static String item = "weapon";
	/** Impact sound: a key from SOUNDS, "combo" or "off". */
	public static String sound = "wither";
	/** Kill effect from MODI. */
	public static String modus = "weapon";
	/** Player name whose skin the assist player wears (empty = your own skin). */
	public static String assistSkin = "";
	/** false = the body stays standing, true = the body dissolves on impact. */
	public static boolean koerperAufloesen = false;

	private HeavensWrathConfig() {
	}

	/** Some sounds are a SoundEvent directly or a Holder of one depending on the version; both become a SoundEvent. */
	private static SoundEvent sound(Object o) {
		return o instanceof net.minecraft.core.Holder<?> h ? (SoundEvent) h.value() : (SoundEvent) o;
	}

	/** Every selectable sound id for the menu. */
	public static List<String> soundNamen() {
		List<String> liste = new ArrayList<>();
		liste.add("off");
		liste.add("combo");
		liste.addAll(SOUNDS.keySet());
		return liste;
	}

	/** English display name for an item/sound id (falls back to a prettified id). */
	public static String label(String id) {
		if ("weapon".equals(id)) {
			return "Your Weapon";
		}
		if ("off".equals(id)) {
			return "Off";
		}
		if ("combo".equals(id)) {
			return "Combo";
		}
		return id.replace('_', ' ');
	}

	/** Plays the chosen impact sound. */
	public static void spieleSound(ClientLevel level, double x, double y, double z) {
		if ("off".equals(sound)) {
			return;
		}
		if ("combo".equals(sound)) {
			level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 8.0f, 0.6f, false);
			level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 6.0f, 0.5f, false);
			level.playLocalSound(x, y, z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 4.0f, 0.4f, false);
			level.playLocalSound(x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 6.0f, 1.0f, false);
			return;
		}
		SoundEvent event = SOUNDS.getOrDefault(sound, SoundEvents.WITHER_SPAWN);
		// Volume above 1 = heard far away (like the vanilla, global Wither-spawn sound).
		float lautstaerke = event == SoundEvents.WITHER_SPAWN ? 10.0f : 8.0f;
		level.playLocalSound(x, y, z, event, SoundSource.HOSTILE, lautstaerke, 1.0f, false);
	}

	/** Plays the sound at your own position, as a preview (used when picking one in the menu). */
	public static void vorschau() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			spieleSound(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
		}
	}

	/** Which item should fall? */
	public static ItemStack waehle(ItemStack gehalten) {
		Item preset = ITEMS.get(item);
		return preset == null ? gehalten : new ItemStack(preset);
	}

	private static Path datei() {
		return FabricLoader.getInstance().getConfigDir().resolve("heavens_wrath.properties");
	}

	private static void aktuellInProfil() {
		Profil p = PROFIL_DATEN[profil];
		p.item = item;
		p.sound = sound;
		p.modus = modus;
		p.assistSkin = assistSkin;
		p.koerperAufloesen = koerperAufloesen;
	}

	private static void profilInAktuell() {
		Profil p = PROFIL_DATEN[profil];
		item = p.item;
		sound = p.sound;
		modus = p.modus;
		assistSkin = p.assistSkin;
		koerperAufloesen = p.koerperAufloesen;
	}

	/** Switches the active profile (saves the current one first). */
	public static void waehleProfil(int nummer) {
		if (nummer < 0 || nummer >= PROFILE) {
			return;
		}
		aktuellInProfil();
		profil = nummer;
		profilInAktuell();
		speichern();
	}

	/** Short summary of a profile for display. */
	public static String profilKurz(int nummer) {
		if (nummer == profil) {
			aktuellInProfil();
		}
		Profil p = PROFIL_DATEN[nummer];
		return displayName(p.modus) + " | " + label(p.item) + " | " + label(p.sound);
	}

	/** Name from the GameProfile, whichever method this version's library offers (name() or getName()). */
	public static String profilName(Object profil) {
		if (profil == null) {
			return "";
		}
		for (String methode : new String[] { "name", "getName" }) {
			try {
				Method m = profil.getClass().getMethod(methode);
				Object r = m.invoke(profil);
				if (r instanceof String s) {
					return s;
				}
			} catch (Throwable ignored) {
				// try the next variant
			}
		}
		return "";
	}

	/** Names of players on the server (for picking the assist player's skin). */
	public static List<String> onlineNamen() {
		List<String> liste = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			for (var info : mc.getConnection().getOnlinePlayers()) {
				String n = profilName(info.getProfile());
				if (n.matches("[A-Za-z0-9_]{1,16}") && !liste.contains(n)) {
					liste.add(n);
				}
			}
		}
		java.util.Collections.sort(liste, String.CASE_INSENSITIVE_ORDER);
		return liste;
	}

	private static String gueltig(String wert, String standard, java.util.function.Predicate<String> ok) {
		return wert != null && ok.test(wert) ? wert : standard;
	}

	public static void laden() {
		Path p = datei();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p)) {
			Properties props = new Properties();
			props.load(r);
			aktiv = Boolean.parseBoolean(props.getProperty("aktiv", "true"));
			try {
				profil = Math.max(0, Math.min(PROFILE - 1, Integer.parseInt(props.getProperty("profil", "0").trim())));
			} catch (NumberFormatException e) {
				profil = 0;
			}
			for (int i = 0; i < PROFILE; i++) {
				Profil pr = PROFIL_DATEN[i];
				String k = "p" + i + ".";
				pr.item = gueltig(props.getProperty(k + "item"), "weapon", v -> "weapon".equals(v) || ITEMS.containsKey(v));
				pr.sound = gueltig(props.getProperty(k + "sound"), "wither",
						v -> "off".equals(v) || "combo".equals(v) || SOUNDS.containsKey(v));
				pr.modus = gueltig(props.getProperty(k + "modus"), "weapon", MODI::contains);
				pr.assistSkin = props.getProperty(k + "assistSkin", "").trim();
				if (!pr.assistSkin.matches("[A-Za-z0-9_]{0,16}")) {
					pr.assistSkin = "";
				}
				pr.koerperAufloesen = Boolean.parseBoolean(props.getProperty(k + "koerperAufloesen", "false"));
			}
			profilInAktuell();
		} catch (IOException e) {
			System.err.println("[Heaven's Wrath] Could not read settings: " + e);
		}
	}

	public static void speichern() {
		aktuellInProfil();
		Properties props = new Properties();
		props.setProperty("aktiv", Boolean.toString(aktiv));
		props.setProperty("profil", Integer.toString(profil));
		for (int i = 0; i < PROFILE; i++) {
			Profil pr = PROFIL_DATEN[i];
			String k = "p" + i + ".";
			props.setProperty(k + "item", pr.item);
			props.setProperty(k + "sound", pr.sound);
			props.setProperty(k + "modus", pr.modus);
			props.setProperty(k + "assistSkin", pr.assistSkin);
			props.setProperty(k + "koerperAufloesen", Boolean.toString(pr.koerperAufloesen));
		}
		// Write to a temp file first, then swap it in: settings are never half-lost this way.
		Path ziel = datei();
		Path temp = ziel.resolveSibling("heavens_wrath.properties.tmp");
		try (Writer w = Files.newBufferedWriter(temp)) {
			props.store(w, "Heaven's Wrath settings");
		} catch (IOException e) {
			System.err.println("[Heaven's Wrath] Could not save settings: " + e);
			return;
		}
		try {
			Files.move(temp, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			System.err.println("[Heaven's Wrath] Could not replace settings file: " + e);
		}
	}
}
