package de.heavenswrath;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.authlib.GameProfile;
import com.mojang.math.Transformation;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.syncher.EntityDataAccessor;

/**
 * Client mod: kill a player in melee and a HUGE copy of your weapon (or whatever item
 * /killeffect has picked) crashes down from the sky onto the block beneath them. Everything
 * happens only on your own screen (no server mod needed).
 *
 * Settings and the in-game menu: {@link HeavensWrathConfig}, {@link HeavensWrathScreen}
 * and {@link HeavensWrathBefehl}.
 */
public class HeavensWrathClient implements ClientModInitializer {

	/** How high above the victim the weapon starts (in blocks). */
	private static final double FALL_HOEHE = 40.0;
	/** Size of the weapon (1 = normal item size, 6 = six blocks tall). */
	private static final float SKALIERUNG = 6.0f;
	/** Fall acceleration per tick (bigger = faster). */
	private static final double GRAVITATION = 0.10;
	/** How long (in ticks) the weapon stays stuck in the ground after impact. */
	private static final int STECKEN_TICKS = 50;
	/** How long (in ticks) after your last hit a death still counts as "your kill". */
	private static final int KILL_FENSTER = 40;
	/**
	 * false = the standing copy wears the victim's own skin (default).
	 * true  = the copy wears YOUR skin instead.
	 */
	private static final boolean KOPIE_MIT_EIGENEM_SKIN = false;
	/** Safety timeout in case the weapon never lands. */
	private static final int MAX_FALL_TICKS = 200;

	/**
	 * How deep the tip sits in the ground. The weapon is rotated 45 degrees, so its tip
	 * ends up about 0.7 * size below its own center.
	 */
	private static final double MITTE_ÜBER_BODEN = 0.7 * SKALIERUNG - 0.4;

	private static class Fallend {
		final Entity entity;
		final boolean riesig;
		final double x;
		final double z;
		final double bodenY;
		double y;
		double vy = 0.0;
		int alter = 0;
		int steckt = -1; // -1 = still falling
		/** Standing copy of the victim; stays until the weapon is gone. */
		Entity koerper = null;
		/** Fire Aspect level on the weapon, 0 = not enchanted with it. */
		int feuer = 0;

		// ---- Attack modes (goat, llama, warden, iron golem, slime); art == null = the normal weapon drop ----
		String art = null;
		double dirX, dirZ; // facing direction toward the victim
		double tx, tz; // current position of the mob/fake player
		int phase = 0; // 0 = approach/wind-up, 1 = the hit happened
		int zaehler = 0;
		int nachTreffer = 0;
		boolean flug = false; // the victim's body is currently flying through the air
		boolean landet = false; // the body lands on the ground (otherwise it just vanishes at the end)
		double kvx, kvy, kvz, grav;
		int flugTicks = 0;
		int maxFlug = 40;
		// ---- Assist player (mace / sword) ----
		double fy, fvy; // height and fall speed of the fake player
		Entity extra = null; // e.g. the huge sword stuck through the body
		ItemStack stack = ItemStack.EMPTY;

		Fallend(Entity entity, boolean riesig, double x, double y, double z, double bodenY) {
			this.entity = entity;
			this.riesig = riesig;
			this.x = x;
			this.y = y;
			this.z = z;
			this.bodenY = bodenY;
		}
	}

	private final List<Fallend> fallende = new ArrayList<>();
	private static int nextClientId = -2_000_000;
	/** Looked-up assist skins by lowercase player name, so a typed name also works when that player is offline. */
	private static final Map<String, GameProfile> ASSIST_PROFIL_CACHE = new ConcurrentHashMap<>();
	/** Names currently being looked up, so we don't fire off the same request every tick. */
	private static final Map<String, Boolean> ASSIST_PROFIL_LAEUFT = new ConcurrentHashMap<>();

	private long tick = 0;
	private int letztesZielId = -1;
	private ItemStack letzteWaffe = ItemStack.EMPTY;
	private long letzterTreffer = -1000;

	@Override
	public void onInitializeClient() {
		HeavensWrathConfig.laden();
		HeavensWrathTaste.registrieren();
		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess) -> HeavensWrathBefehl.registrieren(dispatcher));

		// Remember the local player's last hit (weapon = whatever is in their hand).
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			Minecraft mc = Minecraft.getInstance();
			if (world.isClientSide() && player == mc.player && entity instanceof Player) {
				letztesZielId = entity.getId();
				letzteWaffe = player.getMainHandItem().copy();
				letzterTreffer = tick;
			}
			return InteractionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		HeavensWrathScreen.pruefeOeffnen(mc);
		HeavensWrathTaste.tick(mc);
		tick++;
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			for (Fallend f : fallende) {
				entferne(f);
			}
			fallende.clear();
			letztesZielId = -1;
			return;
		}

		pruefeKill(mc, level);
		aktualisiereFallende(level);
	}

	private void pruefeKill(Minecraft mc, ClientLevel level) {
		if (letztesZielId == -1) {
			return;
		}
		if (tick - letzterTreffer > KILL_FENSTER) {
			letztesZielId = -1;
			return;
		}

		Entity e = level.getEntity(letztesZielId);
		if (e instanceof Player opfer && opfer != mc.player && opfer.isDeadOrDying()) {
			letztesZielId = -1;
			if (!HeavensWrathConfig.aktiv) {
				return;
			}
			double bodenY = findeBoden(level, opfer);

			// Wind Burst on the mace is a bonus effect on top of whatever kill effect is chosen below.
			int windStufe = windBurstStufe(letzteWaffe);
			if (windStufe > 0 && letzteWaffe.is(Items.MACE)) {
				windBurstEffekt(level, opfer.getX(), bodenY, opfer.getZ(), windStufe);
			}

			String modus = HeavensWrathConfig.MODI.contains(HeavensWrathConfig.modus) ? HeavensWrathConfig.modus
					: "weapon";
			switch (modus) {
				case "goat", "llama", "warden", "golem", "lightning", "angel", "slime", "tsundere" -> {
					starteAngriff(level, opfer, bodenY, modus);
					return;
				}
				case "assist" -> {
					// Assist only happens with a mace or a sword; any other weapon falls down as usual.
					if (letzteWaffe.is(Items.MACE) || letzteWaffe.is(ItemTags.SWORDS)) {
						starteAngriff(level, opfer, bodenY, "assist");
						return;
					}
				}
				default -> {
				}
			}
			boolean meteor = "meteor".equals(modus);
			// Which item falls? Default: whatever weapon you killed with. Meteor: a magma block.
			ItemStack anzeige = meteor ? new ItemStack(Items.MAGMA_BLOCK) : HeavensWrathConfig.waehle(letzteWaffe);
			if (anzeige == null || anzeige.isEmpty()) {
				return;
			}
			// The weapon and the body always land on the block BELOW the player.
			starteAufprall(level, opfer.getX(), bodenY, opfer.getZ(), anzeige);
			if (!fallende.isEmpty()) {
				Fallend neu = fallende.get(fallende.size() - 1);
				neu.koerper = erzeugeKoerper(level, opfer, bodenY);
				// Fire follows the real weapon's Fire Aspect level; a meteor always burns strongly.
				neu.feuer = meteor ? 2 : feuerStufe(letzteWaffe);
			}
		}
	}

	/** The block right below the player: the top surface of the first solid block under their feet. */
	private static double findeBoden(ClientLevel level, Player opfer) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(opfer.getBlockX(), opfer.getBlockY(), opfer.getBlockZ());
		int min = level.getMinY();
		while (pos.getY() >= min) {
			BlockState state = level.getBlockState(pos);
			var form = state.getCollisionShape(level, pos);
			if (!form.isEmpty()) {
				return pos.getY() + form.max(Direction.Axis.Y);
			}
			pos.move(Direction.DOWN);
		}
		return opfer.getY(); // e.g. over the void: stay where they died
	}

	private void starteAufprall(ClientLevel level, double x, double bodenY, double z, ItemStack waffe) {
		double startY = Math.min(bodenY + MITTE_ÜBER_BODEN + FALL_HOEHE, level.getMaxY() - 1);

		// Plan A: a huge, rotated weapon (an item-display entity).
		Entity display = erzeugeRiesigesDisplay(level, waffe);
		if (display != null) {
			display.setId(nextClientId--);
			display.setPos(x, startY, z);
			level.addEntity(display);
			fallende.add(new Fallend(display, true, x, startY, z, bodenY));
			return;
		}

		// Plan B: a normal-sized falling item.
		ItemEntity item = new ItemEntity(level, x, startY, z, waffe.copy());
		item.setId(nextClientId--);
		item.setNeverPickUp();
		item.setUnlimitedLifetime();
		item.setDeltaMovement(0.0, -0.8, 0.0);
		level.addEntity(item);
		fallende.add(new Fallend(item, false, x, startY, z, bodenY));
	}

	/** A huge weapon, tip pointing down (weapon sprites point up-right by default, so rotate 135 degrees). */
	private Entity erzeugeRiesigesDisplay(ClientLevel level, ItemStack waffe) {
		Transformation trafo = new Transformation(
				new Vector3f(0, 0, 0),
				(waffe.getItem() instanceof BlockItem)
						? new Quaternionf() // blocks (e.g. the anvil) are not tilted
						: new Quaternionf().rotateZ((float) Math.toRadians(135.0)),
				new Vector3f(SKALIERUNG, SKALIERUNG, SKALIERUNG),
				new Quaternionf());
		return erzeugeDisplay(level, waffe, trafo, Display.BillboardConstraints.CENTER);
	}

	/**
	 * Builds an item-display entity with the given size/rotation. The setters are not public,
	 * so they're reached via reflection (26.x is unobfuscated, so the names match in-game too).
	 */
	private Display.ItemDisplay erzeugeDisplay(ClientLevel level, ItemStack stack, Transformation trafo,
			Display.BillboardConstraints billboard) {
		try {
			Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			// <REFLEKTION>
			Method setItem = Display.ItemDisplay.class.getDeclaredMethod("setItemStack", ItemStack.class);
			setItem.setAccessible(true);
			setItem.invoke(display, stack.copy());

			Method setContext = Display.ItemDisplay.class.getDeclaredMethod("setItemTransform", ItemDisplayContext.class);
			setContext.setAccessible(true);
			setContext.invoke(display, ItemDisplayContext.GUI);

			Method setBillboard = Display.class.getDeclaredMethod("setBillboardConstraints",
					Display.BillboardConstraints.class);
			setBillboard.setAccessible(true);
			setBillboard.invoke(display, billboard);

			Method setTrafo = Display.class.getDeclaredMethod("setTransformation", Transformation.class);
			setTrafo.setAccessible(true);
			setTrafo.invoke(display, trafo);
			// </REFLEKTION>
			return display;
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not create the display entity: " + t);
			return null;
		}
	}

	/**
	 * The real victim ragdolls and is removed by the server after about a second - the client
	 * cannot stop that. So: hide the original and put a standing local copy (same skin, same
	 * armor) on the ground in its place.
	 */
	private Entity erzeugeKoerper(ClientLevel level, Player opfer, double bodenY) {
		try {
			// Pin the skin down: the copy has its own random UUID, so the normal skin lookup
			// would otherwise find nothing and fall back to Steve/Alex.
			Player skinQuelle = KOPIE_MIT_EIGENEM_SKIN && Minecraft.getInstance().player != null
					? Minecraft.getInstance().player
					: opfer;
			PlayerSkin skin = skinQuelle instanceof AbstractClientPlayer acp ? acp.getSkin() : null;

			RemotePlayer kopie = new RemotePlayer(level, opfer.getGameProfile()) {
				@Override
				public PlayerSkin getSkin() {
					return skin != null ? skin : super.getSkin();
				}
			};
			kopie.setUUID(UUID.randomUUID()); // otherwise it clashes with the original
			kopie.setId(nextClientId--);
			kopie.setPos(opfer.getX(), bodenY, opfer.getZ());
			kopie.setNoGravity(true);
			kopie.setYRot(opfer.getYRot());
			kopie.yRotO = opfer.getYRot();
			kopie.setXRot(opfer.getXRot());
			kopie.yBodyRot = opfer.yBodyRot;
			kopie.yBodyRotO = opfer.yBodyRot;
			kopie.yHeadRot = opfer.yHeadRot;
			kopie.yHeadRotO = opfer.yHeadRot;
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				kopie.setItemSlot(slot, opfer.getItemBySlot(slot).copy());
			}
			// Copy the second skin layer too (jacket, sleeves, pants, hat, cape) from the original.
			try {
				var werte = opfer.getEntityData().getNonDefaultValues();
				if (werte != null) {
					kopie.getEntityData().assignValues(werte);
				}
			} catch (Throwable t) {
				System.err.println("[Heaven's Wrath] Could not copy the skin layers: " + t);
			}
			// Undo anything dead/invisible/posed that might have been copied along with it.
			kopie.setInvisible(false);
			kopie.setPose(Pose.STANDING);
			kopie.setHealth(kopie.getMaxHealth());
			level.addEntity(kopie);

			opfer.setInvisible(true); // hide the original (with its death animation)
			return kopie;
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not create the standing body: " + t);
			return null;
		}
	}

	private void entferne(Fallend f) {
		f.entity.discard();
		if (f.koerper != null) {
			f.koerper.discard();
		}
		if (f.extra != null) {
			f.extra.discard();
		}
	}

	private void aktualisiereFallende(ClientLevel level) {
		Iterator<Fallend> it = fallende.iterator();
		while (it.hasNext()) {
			Fallend f = it.next();
			f.alter++;

			if (f.entity.isRemoved()) {
				entferne(f);
				it.remove();
				continue;
			}

			if (f.art != null) {
				if (aktualisiereAngriff(level, f)) {
					entferne(f);
					it.remove();
				}
				continue;
			}

			if (f.riesig) {
				if (f.steckt < 0) {
					f.vy -= GRAVITATION;
					f.y += f.vy;
					if (f.feuer > 0) {
						feuerFlug(level, f);
					}
					double ziel = f.bodenY + MITTE_ÜBER_BODEN;
					if (f.y <= ziel || f.alter > MAX_FALL_TICKS) {
						f.y = ziel;
						f.steckt = 0;
						einschlag(level, f.x, f.bodenY, f.z);
						koerperEffekt(level, f);
						if (f.feuer > 0) {
							feuerEinschlag(level, f, f.x, f.bodenY, f.z);
						}
					}
					f.entity.setPos(f.x, f.y, f.z);
				} else {
					f.steckt++;
					if (f.feuer > 0) {
						feuerAmBoden(level, f);
					}
					if (f.steckt >= STECKEN_TICKS) {
						entferne(f);
						it.remove();
					}
				}
			} else {
				boolean aufgeschlagen = f.entity.onGround()
						|| f.entity.getY() <= f.bodenY + 0.3
						|| f.alter > MAX_FALL_TICKS;
				if (aufgeschlagen) {
					einschlag(level, f.entity.getX(), f.entity.getY(), f.entity.getZ());
					koerperEffekt(level, f);
					if (f.feuer > 0) {
						feuerEinschlag(level, f, f.entity.getX(), f.entity.getY(), f.entity.getZ());
					}
					entferne(f);
					it.remove();
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Attack modes: goat, llama, warden, iron golem (/killeffect mode ...)
	// ------------------------------------------------------------------

	private static final double ZIEGE_TEMPO = 0.9;
	/** How long (in ticks) the mob keeps standing around after it hits. */
	private static final int NACHLAUF_TICKS = 80;

	private void starteAngriff(ClientLevel level, Player opfer, double bodenY, String art) {
		if ("assist".equals(art)) {
			starteAssist(level, opfer, bodenY);
			return;
		}
		if ("slime".equals(art)) {
			starteSlime(level, opfer, bodenY);
			return;
		}
		EntityType<?> typ = switch (art) {
			case "goat" -> EntityType.GOAT;
			case "llama" -> EntityType.LLAMA;
			case "warden" -> EntityType.WARDEN;
			case "golem" -> EntityType.IRON_GOLEM;
			default -> EntityType.ITEM_DISPLAY; // lightning, angel: just an invisible placeholder
		};
		double abstand = switch (art) {
			case "goat" -> 14.0;
			case "llama" -> 6.0;
			case "warden" -> 12.0;
			case "golem" -> 2.4;
			default -> 0.0;
		};
		try {
			Entity tier = typ.create(level, EntitySpawnReason.COMMAND);
			if (tier == null) {
				return;
			}
			double winkel = Math.random() * Math.PI * 2;
			double dirX = -Math.cos(winkel);
			double dirZ = -Math.sin(winkel);
			double x = opfer.getX();
			double z = opfer.getZ();
			double sx = x - dirX * abstand;
			double sz = z - dirZ * abstand;
			float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

			tier.setId(nextClientId--);
			tier.setPos(sx, bodenY, sz);
			tier.setYRot(yaw);
			tier.yRotO = yaw;
			if (tier instanceof LivingEntity le) {
				le.yBodyRot = yaw;
				le.yBodyRotO = yaw;
				le.yHeadRot = yaw;
				le.yHeadRotO = yaw;
			}
			level.addEntity(tier);

			Fallend f = new Fallend(tier, false, x, bodenY, z, bodenY);
			f.art = art;
			f.dirX = dirX;
			f.dirZ = dirZ;
			f.tx = sx;
			f.tz = sz;
			f.koerper = erzeugeKoerper(level, opfer, bodenY);
			if (f.koerper instanceof LivingEntity ko) {
				// A human touch: the body turns to face whatever is charging in, instead of standing
				// frozen in whatever direction it happened to die facing.
				float schauYaw = yaw + 180.0f;
				ko.setYRot(schauYaw);
				ko.yRotO = schauYaw;
				ko.yBodyRot = schauYaw;
				ko.yBodyRotO = schauYaw;
				ko.yHeadRot = schauYaw;
				ko.yHeadRotO = schauYaw;
			}
			fallende.add(f);
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not start the attack: " + t);
		}
	}

	// ------------------------------------------------------------------
	// Lightning
	// ------------------------------------------------------------------

	/** A real lightning bolt (visual only, only on your screen). The thunder comes from the bolt itself. */
	private static void blitz(ClientLevel level, double x, double y, double z) {
		try {
			Entity bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
			if (bolt != null) {
				bolt.setPos(x, y, z);
				bolt.setId(nextClientId--);
				level.addEntity(bolt);
			}
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not strike lightning: " + t);
		}
		for (int i = 0; i < 20; i++) {
			level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y + 0.5, z,
					(Math.random() - 0.5) * 0.8, Math.random() * 0.6, (Math.random() - 0.5) * 0.8);
		}
	}

	// ------------------------------------------------------------------
	// Wind Burst: a bonus effect whenever you kill with a mace enchanted with Wind Burst
	// ------------------------------------------------------------------

	/** Wind Burst level on the weapon (0-3); 0 = not enchanted with it. */
	private static int windBurstStufe(ItemStack waffe) {
		try {
			ItemEnchantments verzauberungen = waffe.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (Holder<Enchantment> h : verzauberungen.keySet()) {
				if (h.is(Enchantments.WIND_BURST)) {
					return Math.max(1, verzauberungen.getLevel(h));
				}
			}
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not read the Wind Burst level: " + t);
		}
		return 0;
	}

	/** A gust of wind at the kill spot; bigger and louder the higher the Wind Burst level. */
	private void windBurstEffekt(ClientLevel level, double x, double y, double z, int stufe) {
		level.playLocalSound(x, y, z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 3.0f + stufe, 1.3f, false);
		int ringe = 1 + stufe;
		int proRing = 14 + stufe * 10;
		for (int ring = 0; ring < ringe; ring++) {
			double radius = 0.6 + ring * 0.9;
			for (int i = 0; i < proRing; i++) {
				double winkel = Math.random() * Math.PI * 2;
				double hoehe = Math.random() * 1.6;
				double dx = Math.cos(winkel);
				double dz = Math.sin(winkel);
				level.addParticle(ParticleTypes.GUST, x + dx * radius, y + hoehe, z + dz * radius,
						dx * 0.25 * stufe, 0.08, dz * 0.25 * stufe);
				level.addParticle(ParticleTypes.CLOUD, x + dx * radius * 0.6, y + hoehe * 0.5, z + dz * radius * 0.6,
						dx * 0.12, 0.12, dz * 0.12);
			}
		}
		if (stufe >= 3) {
			level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.4, z, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------
	// Slime: the victim turns into a slime and hops away
	// ------------------------------------------------------------------

	private void starteSlime(ClientLevel level, Player opfer, double bodenY) {
		try {
			Entity slime = EntityType.SLIME.create(level, EntitySpawnReason.COMMAND);
			if (slime == null) {
				return;
			}
			slime.setId(nextClientId--);
			slime.setPos(opfer.getX(), bodenY, opfer.getZ());
			level.addEntity(slime);
			opfer.setInvisible(true);
			level.playLocalSound(opfer.getX(), bodenY, opfer.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE,
					2.0f, 0.9f, false);
			for (int i = 0; i < 30; i++) {
				level.addParticle(ParticleTypes.ITEM_SLIME, opfer.getX(), bodenY + 0.6, opfer.getZ(),
						(Math.random() - 0.5) * 0.6, Math.random() * 0.4, (Math.random() - 0.5) * 0.6);
			}

			Fallend f = new Fallend(slime, false, opfer.getX(), bodenY, opfer.getZ(), bodenY);
			f.art = "slime";
			double winkel = Math.random() * Math.PI * 2;
			f.dirX = Math.cos(winkel);
			f.dirZ = Math.sin(winkel);
			f.tx = opfer.getX();
			f.tz = opfer.getZ();
			fallende.add(f);
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not turn the victim into a slime: " + t);
		}
	}

	/** Returns true once the slime has hopped away and should disappear. */
	private boolean aktualisiereSlime(ClientLevel level, Fallend f) {
		int t = f.zaehler % 25;
		if (t == 0) {
			if (f.zaehler > 0) {
				level.playLocalSound(f.tx, f.bodenY, f.tz, SoundEvents.SLIME_JUMP, SoundSource.HOSTILE, 1.3f, 1.0f, false);
			}
			double turn = (Math.random() - 0.5) * 1.3;
			double ndx = f.dirX * Math.cos(turn) - f.dirZ * Math.sin(turn);
			double ndz = f.dirX * Math.sin(turn) + f.dirZ * Math.cos(turn);
			double len = Math.max(0.001, Math.sqrt(ndx * ndx + ndz * ndz));
			f.dirX = ndx / len;
			f.dirZ = ndz / len;
		}
		double hop = t < 12 ? Math.sin(t / 12.0 * Math.PI) * 0.55 : 0.0;
		if (t < 12) {
			f.tx += f.dirX * 0.16;
			f.tz += f.dirZ * 0.16;
		}
		f.entity.setPos(f.tx, f.bodenY + hop, f.tz);
		if (t % 4 == 0) {
			level.addParticle(ParticleTypes.ITEM_SLIME, f.tx, f.bodenY + 0.15, f.tz, 0.0, 0.0, 0.0);
		}
		return f.zaehler > 150;
	}

	// ------------------------------------------------------------------
	// Assist: a fake player (skin of your choice) helps with the kill
	//   Mace   -> drops out of the sky and smashes it down
	//   Sword  -> runs in and rams a huge sword straight through the body
	// ------------------------------------------------------------------

	private void starteAssist(ClientLevel level, Player opfer, double bodenY) {
		boolean mace = letzteWaffe.is(Items.MACE);
		double winkel = Math.random() * Math.PI * 2;
		double dirX = -Math.cos(winkel);
		double dirZ = -Math.sin(winkel);
		double x = opfer.getX();
		double z = opfer.getZ();
		double startAbstand = mace ? 1.4 : 9.0;
		double sx = x - dirX * startAbstand;
		double sz = z - dirZ * startAbstand;
		double sy = mace ? Math.min(bodenY + 38.0, level.getMaxY() - 1) : bodenY;
		float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
		ItemStack hand = mace ? new ItemStack(Items.MACE) : letzteWaffe.copy();

		Entity fake = erzeugeAssistSpieler(level, sx, sy, sz, yaw, hand);
		if (fake == null) {
			return;
		}
		Fallend f = new Fallend(fake, false, x, bodenY, z, bodenY);
		f.art = mace ? "assist_mace" : "assist_sword";
		f.dirX = dirX;
		f.dirZ = dirZ;
		f.tx = sx;
		f.tz = sz;
		f.fy = sy;
		f.stack = hand;
		f.koerper = erzeugeKoerper(level, opfer, bodenY);
		fallende.add(f);
	}

	/** Fake player with the skin picked in the menu/config (empty = your own skin). */
	private Entity erzeugeAssistSpieler(ClientLevel level, double x, double y, double z, float yaw, ItemStack hand) {
		try {
			Minecraft mc = Minecraft.getInstance();
			PlayerInfo info = null;
			String wunsch = HeavensWrathConfig.assistSkin;
			if (!wunsch.isEmpty() && mc.getConnection() != null) {
				for (PlayerInfo i : mc.getConnection().getOnlinePlayers()) {
					if (wunsch.equalsIgnoreCase(HeavensWrathConfig.profilName(i.getProfile()))) {
						info = i;
						break;
					}
				}
			}
			GameProfile profil;
			PlayerSkin skin;
			if (info != null) {
				profil = info.getProfile();
				skin = info.getSkin();
			} else if (!wunsch.isEmpty()) {
				// Not online right now - look the name up (works offline too), cached for next time.
				GameProfile gefunden = ermittleAssistProfil(wunsch);
				profil = gefunden != null ? gefunden : mc.player.getGameProfile();
				PlayerSkin gefundenSkin = gefunden != null ? ermittleSkin(mc, gefunden) : null;
				skin = gefundenSkin != null ? gefundenSkin : mc.player.getSkin();
			} else {
				profil = mc.player.getGameProfile();
				skin = mc.player.getSkin();
			}

			RemotePlayer fake = new RemotePlayer(level, profil) {
				@Override
				public PlayerSkin getSkin() {
					return skin != null ? skin : super.getSkin();
				}
			};
			fake.setUUID(UUID.randomUUID());
			fake.setId(nextClientId--);
			fake.setPos(x, y, z);
			fake.setNoGravity(true);
			fake.setYRot(yaw);
			fake.yRotO = yaw;
			fake.yBodyRot = yaw;
			fake.yBodyRotO = yaw;
			fake.yHeadRot = yaw;
			fake.yHeadRotO = yaw;
			fake.setItemSlot(EquipmentSlot.MAINHAND, hand.copy());
			fake.setHealth(fake.getMaxHealth());
			level.addEntity(fake);
			return fake;
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not create the assist player: " + t);
			return null;
		}
	}

	/**
	 * Looks a player name up even when they're offline (Mojang profile + skin, same lookup vanilla
	 * uses for player-head blocks). The lookup itself is async, so the FIRST kill after you type a
	 * new name still falls back to your own skin - the name gets cached as soon as it resolves, and
	 * every kill after that uses the real skin.
	 * Found by reflection (method name/signature for this is found by searching, not called
	 * directly), since the exact method can differ between Minecraft versions.
	 */
	private static GameProfile ermittleAssistProfil(String name) {
		String schluessel = name.toLowerCase();
		GameProfile da = ASSIST_PROFIL_CACHE.get(schluessel);
		if (da != null) {
			return da;
		}
		if (ASSIST_PROFIL_LAEUFT.putIfAbsent(schluessel, Boolean.TRUE) != null) {
			return null;
		}
		try {
			Method m = findeMethode(SkullBlockEntity.class, CompletableFuture.class, String.class);
			if (m == null) {
				ASSIST_PROFIL_LAEUFT.remove(schluessel);
				return null;
			}
			CompletableFuture<?> ergebnis = (CompletableFuture<?>) m.invoke(null, name);
			ergebnis.whenComplete((wert, fehler) -> {
				ASSIST_PROFIL_LAEUFT.remove(schluessel);
				if (wert instanceof Optional<?> opt) {
					opt.ifPresent(p -> {
						if (p instanceof GameProfile gp) {
							ASSIST_PROFIL_CACHE.put(schluessel, gp);
						}
					});
				} else if (wert instanceof GameProfile gp) {
					ASSIST_PROFIL_CACHE.put(schluessel, gp);
				}
			});
		} catch (Throwable t) {
			ASSIST_PROFIL_LAEUFT.remove(schluessel);
			System.err.println("[Heaven's Wrath] Could not look up the player profile: " + t);
		}
		return null;
	}

	/** The skin texture for a resolved profile - found by reflection, see {@link #ermittleAssistProfil}. */
	private static PlayerSkin ermittleSkin(Minecraft mc, GameProfile profil) {
		try {
			Object verwalter = mc.getSkinManager();
			Method m = findeMethode(verwalter.getClass(), PlayerSkin.class, GameProfile.class);
			if (m == null) {
				return null;
			}
			return (PlayerSkin) m.invoke(verwalter, profil);
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not load the looked-up skin: " + t);
			return null;
		}
	}

	/** Finds a method by return type and parameter types instead of by name, since names can differ by version. */
	private static Method findeMethode(Class<?> klasse, Class<?> rueckgabe, Class<?>... parameter) {
		for (Method m : klasse.getMethods()) {
			if (!rueckgabe.isAssignableFrom(m.getReturnType()) || m.getParameterCount() != parameter.length) {
				continue;
			}
			Class<?>[] pt = m.getParameterTypes();
			boolean passt = true;
			for (int i = 0; i < pt.length; i++) {
				if (!pt[i].isAssignableFrom(parameter[i])) {
					passt = false;
					break;
				}
			}
			if (passt) {
				return m;
			}
		}
		return null;
	}

	/** Returns true once the assist is completely finished. */
	private boolean aktualisiereAssist(ClientLevel level, Fallend f) {
		Entity fake = f.entity;
		if ("assist_mace".equals(f.art)) {
			if (f.phase == 0) { // falling out of the sky
				f.fvy -= 0.14;
				f.fy += f.fvy;
				level.addParticle(ParticleTypes.CLOUD, f.tx, f.fy + 1.0, f.tz, 0.0, 0.05, 0.0);
				if (f.fy <= f.bodenY) {
					f.fy = f.bodenY;
					f.phase = 1;
					f.nachTreffer = 0;
					maceSchlag(level, f, true);
				}
				fake.setPos(f.tx, f.fy, f.tz);
				return false;
			}
			f.nachTreffer++;
			fake.setPos(f.tx, f.bodenY, f.tz);
			if (f.nachTreffer == 14 || f.nachTreffer == 28) {
				maceSchlag(level, f, false);
			}
			if (f.nachTreffer == 34) {
				HeavensWrathConfig.spieleSound(level, f.x, f.bodenY, f.z);
				koerperEffekt(level, f);
			}
			return f.nachTreffer > 60;
		}

		// Sword: run in, then ram it through.
		if (f.phase == 0) {
			f.tx += f.dirX * 0.7;
			f.tz += f.dirZ * 0.7;
			double rest = (f.x - f.tx) * f.dirX + (f.z - f.tz) * f.dirZ; // distance still to run
			if (rest <= 1.4) {
				f.tx = f.x - f.dirX * 1.4;
				f.tz = f.z - f.dirZ * 1.4;
				f.phase = 1;
				f.nachTreffer = 0;
				fake.setPos(f.tx, f.bodenY, f.tz);
				schwertStoss(level, f, true);
				return false;
			}
			fake.setPos(f.tx, f.bodenY, f.tz);
			return false;
		}
		f.nachTreffer++;
		fake.setPos(f.tx, f.bodenY, f.tz);
		if (f.nachTreffer == 14 || f.nachTreffer == 28) {
			schwertStoss(level, f, false);
		}
		if (f.nachTreffer == 40) {
			HeavensWrathConfig.spieleSound(level, f.x, f.bodenY, f.z);
			koerperEffekt(level, f);
		}
		return f.nachTreffer > 75;
	}

	/** A mace hit from the assist player (the first one also triggers the impact wave). */
	private void maceSchlag(ClientLevel level, Fallend f, boolean gross) {
		double hy = f.bodenY + 1.0;
		if (f.entity instanceof LivingEntity le) {
			le.swing(InteractionHand.MAIN_HAND);
		}
		level.playLocalSound(f.x, hy, f.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 6.0f, gross ? 0.7f : 1.0f, false);
		level.playLocalSound(f.x, hy, f.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 3.0f, 0.9f, false);
		if (gross) {
			level.addParticle(ParticleTypes.EXPLOSION_EMITTER, f.x, f.bodenY + 0.3, f.z, 0.0, 0.0, 0.0);
			for (int i = 0; i < 40; i++) {
				double w = Math.random() * Math.PI * 2;
				level.addParticle(ParticleTypes.CLOUD, f.x, f.bodenY + 0.2, f.z, Math.cos(w) * 0.4, 0.05, Math.sin(w) * 0.4);
			}
		}
		for (int i = 0; i < 30; i++) {
			level.addParticle(i % 2 == 0 ? ParticleTypes.CRIT : ParticleTypes.DAMAGE_INDICATOR, f.x, hy, f.z,
					(Math.random() - 0.5) * 1.0, Math.random() * 0.6, (Math.random() - 0.5) * 1.0);
		}
		eventSenden(f.koerper, 2); // hit animation on the body
	}

	/** The assist player rams the sword through the body (the huge sword appears on the first stab). */
	private void schwertStoss(ClientLevel level, Fallend f, boolean neu) {
		double hy = f.bodenY + 1.0;
		if (f.entity instanceof LivingEntity le) {
			le.swing(InteractionHand.MAIN_HAND);
			if (neu) {
				le.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // the huge display IS their sword now
			}
		}
		if (neu && !f.stack.isEmpty()) {
			// The blade points away from the assist player, straight through the body.
			float psi = (float) Math.atan2(-f.dirZ, f.dirX);
			Transformation trafo = new Transformation(
					new Vector3f(0, 0, 0),
					new Quaternionf().rotateY(psi).rotateZ((float) Math.toRadians(-135.0)),
					new Vector3f(1.4f, 1.4f, 1.4f),
					new Quaternionf());
			Display.ItemDisplay schwert = erzeugeDisplay(level, f.stack, trafo, Display.BillboardConstraints.FIXED);
			if (schwert != null) {
				schwert.setId(nextClientId--);
				// A touch further through than the body's centre, so the tip still pokes out the other side.
				schwert.setPos(f.x + f.dirX * 0.55, hy, f.z + f.dirZ * 0.55);
				level.addEntity(schwert);
				f.extra = schwert;
			}
		}
		level.playLocalSound(f.x, hy, f.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 4.0f, 0.8f, false);
		level.playLocalSound(f.x, hy, f.z, SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 3.0f, 0.7f, false);
		for (int i = 0; i < 30; i++) {
			level.addParticle(i % 3 == 0 ? ParticleTypes.DAMAGE_INDICATOR : ParticleTypes.CRIT, f.x, hy, f.z,
					(Math.random() - 0.5) * 1.0, Math.random() * 0.5, (Math.random() - 0.5) * 1.0);
		}
		eventSenden(f.koerper, 2);
	}

	/** Plays a mob animation (an entity event); a wrong id for this entity type is harmless. */
	private static void eventSenden(Entity e, int id) {
		try {
			e.handleEntityEvent((byte) id);
		} catch (Throwable ignored) {
			// cosmetic only
		}
	}

	/**
	 * There's no real "crossed arms" pose in vanilla, so this forces the shield-block stance
	 * instead (arms pulled in front of the chest) - closest built-in look to crossed arms.
	 * This reaches a private data field, so it's reflection here (26.x, Mojang names match
	 * in-game) and an Access Widener field entry in 1.21.11.
	 * <REFLEKTION2>
	 */
	private static void koerperVerschraenkteArme(LivingEntity le) {
		try {
			Field feld = LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
			feld.setAccessible(true);
			@SuppressWarnings("unchecked")
			EntityDataAccessor<Byte> flagge = (EntityDataAccessor<Byte>) feld.get(null);
			le.getEntityData().set(flagge, (byte) 3); // bit 0 = using item, bit 1 = off hand -> shield-block stance
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not force the crossed-arms pose: " + t);
		}
		// </REFLEKTION2>
	}

	private static double abstandZumOpfer(Fallend f) {
		double dx = f.x - f.tx;
		double dz = f.z - f.tz;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Returns true once the attack is completely finished. */
	private boolean aktualisiereAngriff(ClientLevel level, Fallend f) {
		f.zaehler++;
		if (f.alter > 400) {
			return true;
		}
		if (f.art.startsWith("assist")) {
			return aktualisiereAssist(level, f);
		}
		if ("slime".equals(f.art)) {
			return aktualisiereSlime(level, f);
		}
		double kopfY = f.bodenY + 1.6;
		switch (f.art) {
			case "goat" -> {
				if (f.zaehler == 1) {
					eventSenden(f.entity, 58); // lower the head
				}
				if (f.phase == 0 || f.nachTreffer < 15) {
					f.tx += f.dirX * ZIEGE_TEMPO;
					f.tz += f.dirZ * ZIEGE_TEMPO;
					level.addParticle(ParticleTypes.POOF, f.tx, f.bodenY + 0.1, f.tz, 0.0, 0.02, 0.0);
				}
				if (f.phase == 0 && abstandZumOpfer(f) < 1.8) {
					level.playLocalSound(f.x, f.bodenY, f.z, SoundEvents.GOAT_RAM_IMPACT, SoundSource.HOSTILE, 3.0f, 1.0f, false);
					treffer(level, f, f.dirX * 1.1, 0.6, f.dirZ * 1.1, 0.08, 45, true);
				}
			}
			case "llama" -> {
				if (f.zaehler == 20) {
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 3.0f, 1.0f, false);
				}
				if (f.zaehler >= 20 && f.zaehler < 28 && f.phase == 0) {
					double t = (f.zaehler - 20) / 8.0;
					for (int i = 0; i < 3; i++) {
						level.addParticle(ParticleTypes.SPIT,
								f.tx + (f.x - f.tx) * t + (Math.random() - 0.5) * 0.15,
								kopfY + (f.bodenY + 1.2 - kopfY) * t + (Math.random() - 0.5) * 0.15,
								f.tz + (f.z - f.tz) * t + (Math.random() - 0.5) * 0.15,
								0.0, 0.0, 0.0);
					}
				}
				if (f.zaehler == 28 && f.phase == 0) {
					for (int i = 0; i < 30; i++) {
						level.addParticle(ParticleTypes.SPIT, f.x, f.bodenY + 1.2, f.z,
								(Math.random() - 0.5) * 0.4, Math.random() * 0.3, (Math.random() - 0.5) * 0.4);
					}
					treffer(level, f, f.dirX * 1.7, 0.9, f.dirZ * 1.7, 0.06, 50, false);
				}
			}
			case "warden" -> {
				if (f.zaehler == 1) {
					eventSenden(f.entity, 62); // sonic boom wind-up
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 4.0f, 1.0f, false);
				}
				if (f.zaehler == 34 && f.phase == 0) {
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 6.0f, 1.0f, false);
					double dx = f.x - f.tx;
					double dz = f.z - f.tz;
					double dist = Math.sqrt(dx * dx + dz * dz);
					for (int i = 1; i <= (int) dist; i++) {
						double t = i / dist;
						level.addParticle(ParticleTypes.SONIC_BOOM, f.tx + dx * t,
								kopfY + (f.bodenY + 1.0 - kopfY) * t, f.tz + dz * t, 0.0, 0.0, 0.0);
					}
					treffer(level, f, f.dirX * 3.2, 0.3, f.dirZ * 3.2, 0.04, 35, false);
				}
			}
			case "lightning" -> { // three bolts in a row
				if (f.zaehler == 8 || f.zaehler == 24 || f.zaehler == 40) {
					blitz(level, f.x + (Math.random() - 0.5) * 0.6, f.bodenY, f.z + (Math.random() - 0.5) * 0.6);
				}
				if (f.zaehler == 41 && f.phase == 0) {
					f.phase = 1;
					f.nachTreffer = 0;
					HeavensWrathConfig.spieleSound(level, f.x, f.bodenY, f.z);
					koerperBrennt(f);
					koerperEffekt(level, f);
				}
			}
			case "angel" -> { // a beam of light carries the body upward, spinning
				if (f.zaehler == 1) {
					f.phase = 1;
					f.flug = f.koerper != null;
					f.kvx = 0.0;
					f.kvy = 0.16;
					f.kvz = 0.0;
					f.grav = 0.0;
					f.maxFlug = 70;
					f.landet = false;
					f.flugTicks = 0;
					level.playLocalSound(f.x, f.bodenY, f.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 6.0f, 1.2f, false);
				}
				if (f.zaehler < 75) {
					for (int i = 0; i < 6; i++) {
						level.addParticle(ParticleTypes.END_ROD, f.x + (Math.random() - 0.5) * 0.5,
								f.bodenY + Math.random() * 40.0, f.z + (Math.random() - 0.5) * 0.5, 0.0, 0.0, 0.0);
					}
				}
				if (f.koerper != null) {
					float drehung = f.zaehler * 12.0f;
					f.koerper.setYRot(drehung);
					f.koerper.yRotO = drehung;
					if (f.koerper instanceof LivingEntity le) {
						le.yBodyRot = drehung;
						le.yBodyRotO = drehung;
						le.yHeadRot = drehung;
						le.yHeadRotO = drehung;
					}
				}
			}
			case "golem" -> { // iron golem punches the enemy "to the moon"
				if (f.zaehler == 15 && f.phase == 0) {
					eventSenden(f.entity, 4); // punch animation
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 3.0f, 0.8f, false);
					treffer(level, f, f.dirX * 0.15, 3.0, f.dirZ * 0.15, 0.0, 50, false);
				}
			}
			case "tsundere" -> { // crosses their arms, huffs, and looks pointedly away
				if (f.zaehler == 1) {
					f.phase = 1;
					f.nachTreffer = 0;
					if (f.koerper != null) {
						float weg = f.koerper.getYRot() + 90.0f;
						f.koerper.setYRot(weg);
						f.koerper.yRotO = weg;
						if (f.koerper instanceof LivingEntity le) {
							le.yBodyRot = weg;
							le.yBodyRotO = weg;
							le.yHeadRot = weg;
							le.yHeadRotO = weg;
							le.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
							koerperVerschraenkteArme(le); // crossed-arms pose - best effort, see method comment
						}
					}
					level.playLocalSound(f.x, f.bodenY + 1.0, f.z, SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 2.0f, 0.7f,
							false);
					for (int i = 0; i < 20; i++) {
						level.addParticle(ParticleTypes.ANGRY_VILLAGER, f.x + (Math.random() - 0.5) * 0.6, f.bodenY + 1.8,
								f.z + (Math.random() - 0.5) * 0.6, 0.0, 0.0, 0.0);
					}
				}
				if (f.zaehler == 70) {
					koerperEffekt(level, f);
				}
			}
		}
		f.entity.setPos(f.tx, f.bodenY, f.tz);
		fliegen(level, f);

		if (f.phase == 1) {
			f.nachTreffer++;
			return f.nachTreffer > NACHLAUF_TICKS;
		}
		return false;
	}

	/** The victim gets hit: an impact effect, then the body launches off with the given velocity. */
	private void treffer(ClientLevel level, Fallend f, double vx, double vy, double vz, double grav, int maxFlug,
			boolean landet) {
		f.phase = 1;
		f.nachTreffer = 0;
		f.flug = f.koerper != null;
		f.kvx = vx;
		f.kvy = vy;
		f.kvz = vz;
		f.grav = grav;
		f.maxFlug = maxFlug;
		f.landet = landet;
		f.flugTicks = 0;
		for (int i = 0; i < 40; i++) {
			level.addParticle(ParticleTypes.CRIT, f.x, f.bodenY + 1.0, f.z,
					(Math.random() - 0.5) * 1.2, Math.random() * 0.8, (Math.random() - 0.5) * 1.2);
		}
		level.addParticle(ParticleTypes.EXPLOSION, f.x, f.bodenY + 1.0, f.z, 0.0, 0.0, 0.0);
		if (f.koerper != null) {
			eventSenden(f.koerper, 2); // flinch/hurt animation, same human touch as the assist effects
		}
	}

	private void fliegen(ClientLevel level, Fallend f) {
		if (!f.flug || f.koerper == null) {
			return;
		}
		Entity k = f.koerper;
		f.kvy -= f.grav;
		f.flugTicks++;
		double nx = k.getX() + f.kvx;
		double ny = k.getY() + f.kvy;
		double nz = k.getZ() + f.kvz;

		if (f.landet && ny <= f.bodenY && f.kvy < 0) {
			k.setPos(nx, f.bodenY, nz);
			f.flug = false;
			return;
		}
		if (f.flugTicks >= f.maxFlug) {
			verpuffen(level, f, nx, ny, nz);
			k.discard();
			f.koerper = null;
			f.flug = false;
			return;
		}
		k.setPos(nx, ny, nz);
		level.addParticle("golem".equals(f.art) ? ParticleTypes.FIREWORK
				: "angel".equals(f.art) ? ParticleTypes.END_ROD : ParticleTypes.CLOUD,
				nx, ny + 1.0, nz, 0.0, 0.0, 0.0);
	}

	/** End of the flight: the victim vanishes (fireworks in the sky for the golem's moon punch). */
	private void verpuffen(ClientLevel level, Fallend f, double x, double y, double z) {
		if ("angel".equals(f.art)) {
			level.playLocalSound(x, y, z, SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 8.0f, 1.0f, false);
			for (int i = 0; i < 80; i++) {
				level.addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.0, z,
						(Math.random() - 0.5) * 1.4, Math.random() * 1.2, (Math.random() - 0.5) * 1.4);
			}
			return;
		}
		if ("golem".equals(f.art)) {
			level.playLocalSound(x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 8.0f, 0.8f, false);
			for (int i = 0; i < 60; i++) {
				level.addParticle(ParticleTypes.FIREWORK, x, y + 1.0, z,
						(Math.random() - 0.5) * 1.2, (Math.random() - 0.5) * 1.2, (Math.random() - 0.5) * 1.2);
			}
		} else {
			for (int i = 0; i < 30; i++) {
				level.addParticle(ParticleTypes.POOF, x, y + 1.0, z,
						(Math.random() - 0.5) * 0.6, (Math.random() - 0.5) * 0.6, (Math.random() - 0.5) * 0.6);
			}
		}
	}

	/** On impact: the body either dissolves, or just loses its armor and weapons (see /killeffect). */
	private void koerperEffekt(ClientLevel level, Fallend f) {
		if (HeavensWrathConfig.koerperAufloesen) {
			koerperLoestAuf(level, f);
		} else {
			ruestungVerschwindet(level, f);
		}
	}

	/**
	 * On impact, the standing copy's armor and weapons simply vanish off its body (with a
	 * little smoke). The copy itself stays (now bare) until the weapon is gone.
	 */
	private void ruestungVerschwindet(ClientLevel level, Fallend f) {
		if (!(f.koerper instanceof LivingEntity opfer)) {
			return;
		}
		double x = opfer.getX();
		double y = opfer.getY();
		double z = opfer.getZ();

		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (opfer.getItemBySlot(slot).isEmpty()) {
				continue;
			}
			double hoehe = switch (slot) {
				case HEAD -> 1.7;
				case CHEST -> 1.2;
				case LEGS -> 0.7;
				case FEET -> 0.2;
				default -> 1.0; // main hand and off hand
			};
			for (int i = 0; i < 6; i++) {
				level.addParticle(ParticleTypes.POOF, x, y + hoehe, z,
						(Math.random() - 0.5) * 0.2, 0.05 + Math.random() * 0.1, (Math.random() - 0.5) * 0.2);
			}
			opfer.setItemSlot(slot, ItemStack.EMPTY);
		}
	}

	/** The body crumbles into ash and soul particles and is gone. */
	private void koerperLoestAuf(ClientLevel level, Fallend f) {
		if (f.koerper == null) {
			return;
		}
		double x = f.koerper.getX();
		double y = f.koerper.getY();
		double z = f.koerper.getZ();
		for (int i = 0; i < 100; i++) {
			double hoehe = Math.random() * 1.9;
			level.addParticle(i % 2 == 0 ? ParticleTypes.ASH : ParticleTypes.SOUL,
					x + (Math.random() - 0.5) * 0.7, y + hoehe, z + (Math.random() - 0.5) * 0.7,
					(Math.random() - 0.5) * 0.15, 0.05 + Math.random() * 0.12, (Math.random() - 0.5) * 0.15);
		}
		for (int i = 0; i < 25; i++) {
			level.addParticle(ParticleTypes.LARGE_SMOKE, x + (Math.random() - 0.5) * 0.6, y + Math.random() * 1.8,
					z + (Math.random() - 0.5) * 0.6, 0.0, 0.08, 0.0);
		}
		f.koerper.discard();
		f.koerper = null;
	}

	/** Fire Aspect level on the weapon; 0 = not enchanted with it. */
	private static int feuerStufe(ItemStack waffe) {
		try {
			ItemEnchantments verzauberungen = waffe.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (Holder<Enchantment> h : verzauberungen.keySet()) {
				if (h.is(Enchantments.FIRE_ASPECT)) {
					return Math.max(1, verzauberungen.getLevel(h));
				}
			}
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Could not read the Fire Aspect level: " + t);
		}
		return 0;
	}

	/** Flames and smoke around the burning weapon while it falls (level 2+ is noticeably more). */
	private void feuerFlug(ClientLevel level, Fallend f) {
		boolean stark = f.feuer >= 2;
		int anzahl = stark ? 18 : 6;
		double breite = SKALIERUNG * 0.3;
		double hoehe = SKALIERUNG * 0.6;
		for (int i = 0; i < anzahl; i++) {
			double px = f.x + (Math.random() - 0.5) * breite * 2;
			double py = f.y + (Math.random() - 0.5) * hoehe * 2;
			double pz = f.z + (Math.random() - 0.5) * breite * 2;
			level.addParticle(ParticleTypes.FLAME, px, py, pz, 0.0, 0.05, 0.0);
			if (i % 3 == 0) {
				level.addParticle(ParticleTypes.SMOKE, px, py, pz, 0.0, 0.08, 0.0);
			}
			if (stark && i % 4 == 0) {
				level.addParticle(ParticleTypes.LAVA, px, py, pz, 0.0, 0.0, 0.0);
				level.addParticle(ParticleTypes.LARGE_SMOKE, px, py, pz, 0.0, 0.1, 0.0);
			}
		}
	}

	/** Fire wave on impact; the body catches fire. Level 2+: bigger wave, a pillar of flame, lava. */
	private void feuerEinschlag(ClientLevel level, Fallend f, double x, double y, double z) {
		boolean stark = f.feuer >= 2;
		level.playLocalSound(x, y, z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 4.0f, 0.5f, false);
		if (stark) {
			level.playLocalSound(x, y, z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 4.0f, 0.5f, false);
		}

		double radius = stark ? 4.5 : 2.5;
		int ring = stark ? 120 : 40;
		for (int i = 0; i < ring; i++) {
			double winkel = Math.random() * Math.PI * 2;
			double r = Math.random() * radius;
			double dx = Math.cos(winkel);
			double dz = Math.sin(winkel);
			level.addParticle(ParticleTypes.FLAME, x + dx * r, y + 0.1, z + dz * r,
					dx * 0.15, 0.1 + Math.random() * 0.2, dz * 0.15);
		}
		if (stark) {
			for (int i = 0; i < 40; i++) {
				level.addParticle(ParticleTypes.FLAME, x + (Math.random() - 0.5) * 1.2, y + i * 0.35,
						z + (Math.random() - 0.5) * 1.2, 0.0, 0.2, 0.0);
			}
			for (int i = 0; i < 30; i++) {
				level.addParticle(ParticleTypes.LAVA, x + (Math.random() - 0.5) * radius, y + 0.3,
						z + (Math.random() - 0.5) * radius, 0.0, 0.0, 0.0);
			}
		}
		koerperBrennt(f);
	}

	/** Flames on the ground and on the body, for as long as the weapon stays stuck there. */
	private void feuerAmBoden(ClientLevel level, Fallend f) {
		boolean stark = f.feuer >= 2;
		double radius = stark ? 3.5 : 1.8;
		int anzahl = stark ? 14 : 5;
		for (int i = 0; i < anzahl; i++) {
			double winkel = Math.random() * Math.PI * 2;
			double r = Math.random() * radius;
			level.addParticle(ParticleTypes.FLAME, f.x + Math.cos(winkel) * r, f.bodenY + 0.1,
					f.z + Math.sin(winkel) * r, 0.0, 0.05 + Math.random() * 0.08, 0.0);
			if (stark && i % 3 == 0) {
				level.addParticle(ParticleTypes.LARGE_SMOKE, f.x + Math.cos(winkel) * r, f.bodenY + 0.5,
						f.z + Math.sin(winkel) * r, 0.0, 0.08, 0.0);
			}
		}
		koerperBrennt(f);
	}

	/** Makes the standing copy visibly on fire (the fire overlay). */
	private void koerperBrennt(Fallend f) {
		if (f.koerper == null) {
			return;
		}
		try {
			Method m = Entity.class.getDeclaredMethod("setSharedFlagOnFire", boolean.class);
			m.setAccessible(true);
			m.invoke(f.koerper, true);
		} catch (Throwable t) {
			// Cosmetic only - everything else still works without the fire overlay.
		}
	}

	/** Impact with the sound chosen via /killsound (default: Wither-spawn). */
	private void einschlag(ClientLevel level, double x, double y, double z) {
		HeavensWrathConfig.spieleSound(level, x, y, z);

		level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5, z, 0, 0, 0);
		for (int i = 0; i < 60; i++) {
			level.addParticle(ParticleTypes.CRIT, x, y + 0.3, z,
					(Math.random() - 0.5) * 1.6, Math.random() * 1.0, (Math.random() - 0.5) * 1.6);
		}
		for (int i = 0; i < 25; i++) {
			level.addParticle(ParticleTypes.POOF, x, y + 0.2, z,
					(Math.random() - 0.5) * 0.5, 0.05, (Math.random() - 0.5) * 0.5);
		}
	}
}
