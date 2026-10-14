package no.kvaksrud.nocreeperexplodes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodesConfig;
import no.kvaksrud.nocreeperexplodes.client.WireCutScreen;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Plays the mod in a real client: the settings file, too far, a longer defuse distance, a frozen beeping creeper,
 * a second player, a defuser who leaves the game or dies, defuse, try to relight, defuse twice, reload, the blasts
 * for a wrong wire and for running out of time, walking away with Esc or by getting pushed off, and a smaller blast setting.
 * Screenshots land in build/run/clientGameTest/screenshots (prefixed nce_).
 */
@SuppressWarnings("UnstableApiUsage")
public class NoCreeperExplodesClientGameTest implements FabricClientGameTest {
	private static final NoCreeperExplodesConfig DEFAULTS = NoCreeperExplodesConfig.DEFAULTS;
	private static final NoCreeperExplodesConfig ALWAYS_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 100.0, DEFAULTS.explosionSizeMultiplier());
	private static final NoCreeperExplodesConfig NEVER_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 0.0, DEFAULTS.explosionSizeMultiplier());

	@Override
	public void runTest(ClientGameTestContext context) {
		AtomicInteger beeps = new AtomicInteger();
		SoundEventListener beepCounter = (sound, _, _) -> {
			if (sound.getIdentifier().equals(NoCreeperExplodes.DEFUSE_BEEP.location())) {
				beeps.incrementAndGet();
			}
		};
		context.runOnClient(client -> client.getSoundManager().addListener(beepCounter));

		TestWorldSave save;
		UUID luckyId;

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(creator -> creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
				.create()) {
			save = singleplayer.getWorldSave();
			singleplayer.getServer().runCommand("difficulty easy");
			singleplayer.getClientLevel().waitForChunksRender();
			MixinEnvironment.getCurrentEnvironment().audit();

			// The settings file is written on first start, and holds the defaults.
			Path configFile = FabricLoader.getInstance().getConfigDir().resolve(NoCreeperExplodesConfig.FILE_NAME);
			check(Files.isRegularFile(configFile), "Expected the settings file at " + configFile);
			check(NoCreeperExplodes.config().equals(DEFAULTS), "Expected the default settings, got " + NoCreeperExplodes.config());

			// More than 1 block away: nothing starts.
			Creeper distant = spawnCreeperFacingPlayer(context, singleplayer, 0, 3, true);
			lookAt(context, distant);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(5);
			check(context.computeOnClient(client -> client.screen == null), "No panel should open from 3 blocks away");
			singleplayer.getServer().runOnServer(server -> check(!distant.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "No defuse session from 3 blocks away"));

			// With a 3-block defuse distance it does, and the panel trusts the server's distance rather than closing.
			NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(3.0, DEFAULTS.successChancePercent(), DEFAULTS.explosionSizeMultiplier()));
			openPanel(context, singleplayer, distant);
			context.waitTicks(10);
			check(context.computeOnClient(client -> client.screen instanceof WireCutScreen), "The panel should stay open 3 blocks away with a 3-block distance");
			removeAndClosePanel(context, singleplayer, distant);
			NoCreeperExplodes.setConfig(DEFAULTS);

			// While someone is defusing, the creeper is frozen, it beeps, and nobody else can take over.
			Creeper frozen = spawnCreeperFacingPlayer(context, singleplayer, -1, 0, false);
			beeps.set(0);
			openPanel(context, singleplayer, frozen);
			Vec3 frozenAt = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = serverPlayer(server);
				NoCreeperExplodes.tryStartDefuse(FakePlayer.get(server.overworld()), frozen);
				DefuseSession session = frozen.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				check(session != null && session.player().equals(player.getUUID()), "A second player must not take over the defuse");
				// A laggy double click from the same player must not reopen (and so walk away from) the panel.
				NoCreeperExplodes.tryStartDefuse(player, frozen);
				frozen.getNavigation().moveTo(frozen.getX() - 8, frozen.getY(), frozen.getZ(), 1.0);
				return frozen.position();
			});
			context.waitTicks(40);
			context.takeScreenshot("nce_00_beeping_panel");
			singleplayer.getServer().runOnServer(server -> {
				check(frozen.position().distanceTo(frozenAt) < 0.05, "The creeper must not move while being defused");
				DefuseSession session = frozen.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				check(session != null && !session.walkedAway(), "A repeated click must not end the running defuse");
			});
			check(context.computeOnClient(client -> client.screen instanceof WireCutScreen), "The panel should still be open");
			check(beeps.get() >= 2, "Expected the bomb to beep while defusing, heard " + beeps.get());
			removeAndClosePanel(context, singleplayer, frozen);

			// A defuser who leaves the game lets the creeper go.
			Creeper abandoned = spawnCreeperFacingPlayer(context, singleplayer, 1, 0, true);
			singleplayer.getServer().runOnServer(server -> abandoned.setAttached(NoCreeperExplodes.DEFUSE_SESSION, DefuseSession.start(UUID.randomUUID(), abandoned.level().getGameTime())));
			context.waitTicks(5);
			singleplayer.getServer().runOnServer(server -> {
				check(!abandoned.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "A defuser who left the game should release the creeper");
				check(abandoned.isAlive() && !NoCreeperExplodes.isHeld(abandoned), "A released creeper is an ordinary creeper again");
				abandoned.discard();
			});

			// Tools from the defuse_tools list work like an empty hand; anything else does nothing.
			Creeper tooled = spawnCreeperFacingPlayer(context, singleplayer, 1, 0, true);
			holdInMainHand(context, singleplayer, new ItemStack(Items.STICK));
			rightClickDoesNothing(context, singleplayer, tooled, "A stick isn't on the default defuse_tools list");
			holdInMainHand(context, singleplayer, new ItemStack(Items.DIAMOND_SHOVEL));
			openPanel(context, singleplayer, tooled);
			removeAndClosePanel(context, singleplayer, tooled);

			// The list is a setting: here only a stick is allowed, so the shovel no longer is.
			NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), DEFAULTS.successChancePercent(), DEFAULTS.explosionSizeMultiplier(), List.of("minecraft:stick")));
			Creeper sticky = spawnCreeperFacingPlayer(context, singleplayer, 1, 0, true);
			rightClickDoesNothing(context, singleplayer, sticky, "A shovel isn't on a defuse_tools list of just minecraft:stick");
			holdInMainHand(context, singleplayer, new ItemStack(Items.STICK));
			openPanel(context, singleplayer, sticky);
			removeAndClosePanel(context, singleplayer, sticky);
			NoCreeperExplodes.setConfig(DEFAULTS);
			holdInMainHand(context, singleplayer, ItemStack.EMPTY);

			// With a 100% chance any wire defuses it.
			NoCreeperExplodes.setConfig(ALWAYS_SAFE);
			Creeper lucky = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			luckyId = lucky.getUUID();
			context.takeScreenshot("nce_01_creeper_before");
			openPanel(context, singleplayer, lucky);
			context.takeScreenshot("nce_02_wire_panel");
			hoverWire(context, 2);
			context.takeScreenshot("nce_03_wire_hover");
			hoverWire(context, 1);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTick();
			context.takeScreenshot("nce_04_wire_snip");
			context.waitFor(client -> client.level.getEntity(luckyId) instanceof Creeper c && NoCreeperExplodes.isDefused(c));
			context.waitTicks(2);
			context.takeScreenshot("nce_05_defused_panel");
			context.waitForScreen(null);
			singleplayer.getServer().runOnServer(server -> check(lucky.isAlive() && NoCreeperExplodes.isDefused(lucky), "A safe cut should defuse the creeper"));
			NoCreeperExplodes.setConfig(DEFAULTS);
			context.waitTicks(5);
			context.takeScreenshot("nce_06_tear");

			// A defused creeper can't be lit any more, by flint and steel or otherwise.
			singleplayer.getServer().runOnServer(server -> serverPlayer(server).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL)));
			context.waitTicks(5);
			lookAt(context, lucky);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(5);
			singleplayer.getServer().runOnServer(server -> {
				check(!lucky.isIgnited(), "Flint and steel should not ignite a defused creeper");
				serverPlayer(server).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				lucky.ignite();
				lucky.setSwellDir(1);
			});
			context.waitTicks(60);
			singleplayer.getServer().runOnServer(server -> {
				check(lucky.isAlive(), "A defused creeper must never explode");
				check(lucky.getSwellDir() < 0 && lucky.getSwelling(1.0F) <= 0.0F, "A defused creeper must not swell");
			});

			// ...and it can't be defused a second time.
			lookAt(context, lucky);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(5);
			check(context.computeOnClient(client -> client.screen == null), "A defused creeper must not open the panel again");
			singleplayer.getServer().runOnServer(server -> check(!lucky.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "A defused creeper must not start a new session"));

			// Dying with the panel open lets the creeper go rather than setting it off.
			Creeper survivor = spawnCreeperFacingPlayer(context, singleplayer, 0, -1, true);
			UUID survivorId = survivor.getUUID();
			openPanel(context, singleplayer, survivor);
			singleplayer.getServer().runCommand("kill @p");
			context.waitForScreen(DeathScreen.class);
			context.waitTicks(5);
			singleplayer.getServer().runOnServer(server -> {
				check(!survivor.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Dying should release the creeper, not walk away from it");
				check(survivor.isAlive() && !NoCreeperExplodes.isHeld(survivor), "Dying should leave an ordinary creeper");
			});
			// A dead player keeps no chunks loaded, so the creeper may unload; it must still be there after respawning.
			context.waitTicks(DefuseSession.WALK_AWAY_TICKS);
			context.clickScreenButton("deathScreen.respawn");
			context.waitForScreen(null);
			singleplayer.getClientLevel().waitForChunksRender();
			waitForServer(context, singleplayer, server -> server.overworld().getEntity(survivorId) instanceof Creeper c && c.isAlive(), 100);
			singleplayer.getServer().runOnServer(server -> server.overworld().getEntity(survivorId).discard());
			// Let the action-bar messages fade so they don't show up in later screenshots.
			context.waitTicks(80);
		}

		// Defused is saved with the creeper and synced back to the client after a reload.
		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getClientLevel().waitForChunksRender();
			context.waitFor(client -> client.level.getEntity(luckyId) instanceof Creeper c && NoCreeperExplodes.isDefused(c));
			singleplayer.getServer().runOnServer(server -> check(
					server.overworld().getEntity(luckyId) instanceof Creeper c && NoCreeperExplodes.isDefused(c),
					"Defused should survive a save and reload"));
			context.takeScreenshot("nce_07_tear_after_reload");

			// With a 0% chance the wire blows it up, 5x bigger than normal. The blasts come last, since they leave big craters.
			NoCreeperExplodes.setConfig(NEVER_SAFE);
			Creeper unlucky = spawnCreeperFacingPlayer(context, singleplayer, 0, -1, true);
			BlockPos ground = BlockPos.containing(unlucky.position()).below();
			openPanel(context, singleplayer, unlucky);
			hoverWire(context, 0);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			waitForServer(context, singleplayer, server -> unlucky.isRemoved(), 40);
			context.waitForScreen(null);
			checkCrater(singleplayer, ground, true);
			NoCreeperExplodes.setConfig(DEFAULTS);
			context.waitTicks(10);
			context.takeScreenshot("nce_08_crater");

			// Out of time: just as big a bang. Move to fresh ground first, away from the first crater.
			moveTo(singleplayer, 60);
			Creeper tooSlow = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			BlockPos tooSlowGround = BlockPos.containing(tooSlow.position()).below();
			openPanel(context, singleplayer, tooSlow);
			singleplayer.getServer().runOnServer(server -> {
				// Fast-forward: one second left on the clock.
				DefuseSession session = tooSlow.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				tooSlow.setAttached(NoCreeperExplodes.DEFUSE_SESSION, new DefuseSession(session.player(), tooSlow.level().getGameTime() + 20, false));
			});
			waitForServer(context, singleplayer, server -> tooSlow.isRemoved(), 60);
			context.waitForScreen(null);
			checkCrater(singleplayer, tooSlowGround, true);

			// Esc walks away from the bomb: it beeps frantically, can't be picked up again, and goes off within 3 seconds.
			moveTo(singleplayer, 120);
			Creeper leftBehind = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			BlockPos leftBehindGround = BlockPos.containing(leftBehind.position()).below();
			openPanel(context, singleplayer, leftBehind);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(null);
			context.waitTicks(5);
			beeps.set(0);
			long walkedAwayAt = singleplayer.getServer().computeOnServer(server -> {
				DefuseSession session = leftBehind.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				long now = leftBehind.level().getGameTime();
				check(session != null && session.walkedAway(), "Esc should walk away from the bomb, not defuse or release it");
				check(session.expiresAt() - now <= DefuseSession.WALK_AWAY_TICKS, "Walking away should leave at most 3 seconds, got " + (session.expiresAt() - now) + " ticks");
				check(leftBehind.isAlive(), "Walking away gives a moment to run");
				return now;
			});
			lookAt(context, leftBehind);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(20);
			check(context.computeOnClient(client -> client.screen == null), "An armed bomb must not open the panel again");
			check(beeps.get() >= 7, "Expected frantic beeping after walking away, heard " + beeps.get() + " beeps in a second");
			singleplayer.getServer().runOnServer(server -> {
				// Not even a bystander can pick it up once the defuser has left the game.
				DefuseSession armed = leftBehind.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				leftBehind.setAttached(NoCreeperExplodes.DEFUSE_SESSION, new DefuseSession(UUID.randomUUID(), armed.expiresAt(), true));
				FakePlayer bystander = FakePlayer.get(server.overworld());
				bystander.setPos(leftBehind.getX() - 1.0, leftBehind.getY(), leftBehind.getZ());
				NoCreeperExplodes.tryStartDefuse(bystander, leftBehind);
				DefuseSession after = leftBehind.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				check(after != null && after.walkedAway(), "Nobody can pick an armed bomb back up");
			});
			context.takeScreenshot("nce_09_walked_away");
			waitForServer(context, singleplayer, server -> leftBehind.isRemoved(), DefuseSession.WALK_AWAY_TICKS + 20);
			long blewUpAt = singleplayer.getServer().computeOnServer(server -> server.overworld().getGameTime());
			check(blewUpAt - walkedAwayAt <= DefuseSession.WALK_AWAY_TICKS + 2, "Should go off within 3 seconds of walking away, took " + (blewUpAt - walkedAwayAt) + " ticks");
			checkCrater(singleplayer, leftBehindGround, true);

			// Getting pushed out of reach with the panel open is walking away too.
			moveTo(singleplayer, 180);
			Creeper pushedOff = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			BlockPos pushedOffGround = BlockPos.containing(pushedOff.position()).below();
			openPanel(context, singleplayer, pushedOff);
			singleplayer.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~-4");
			context.waitForScreen(null);
			singleplayer.getServer().runOnServer(server -> {
				DefuseSession session = pushedOff.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				check(session != null && session.walkedAway(), "Getting out of reach should walk away from the bomb");
			});
			waitForServer(context, singleplayer, server -> pushedOff.isRemoved(), DefuseSession.WALK_AWAY_TICKS + 20);
			checkCrater(singleplayer, pushedOffGround, true);

			// The blast size is a setting: at 1x it's an ordinary creeper blast.
			moveTo(singleplayer, 240);
			NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 0.0, 1.0));
			Creeper small = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			BlockPos smallGround = BlockPos.containing(small.position()).below();
			openPanel(context, singleplayer, small);
			hoverWire(context, 0);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			waitForServer(context, singleplayer, server -> small.isRemoved(), 40);
			context.waitForScreen(null);
			checkCrater(singleplayer, smallGround, false);
			NoCreeperExplodes.setConfig(DEFAULTS);
		}

		context.runOnClient(client -> client.getSoundManager().removeListener(beepCounter));
	}

	/**
	 * The blast always takes out the ground under the creeper. A normal creeper blast can't reach ground 6 blocks
	 * away; a 5x one tears straight through it.
	 */
	private static void checkCrater(TestSingleplayerContext singleplayer, BlockPos ground, boolean big) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			check(level.getBlockState(ground).isAir(), "Expected the blast to take out the ground under the creeper");
			int destroyed = 0;

			for (BlockPos pos : new BlockPos[] {ground.east(6), ground.west(6), ground.north(6), ground.south(6)}) {
				if (level.getBlockState(pos).isAir()) {
					destroyed++;
				}
			}

			if (big) {
				check(destroyed == 4, "Expected a 5x crater reaching 6 blocks out, but only " + destroyed + "/4 spots were blown away");
			} else {
				check(destroyed == 0, "Expected a normal-sized crater, but " + destroyed + "/4 spots 6 blocks out were blown away");
			}
		});
	}

	/** Fresh, flat ground away from earlier craters. */
	private static void moveTo(TestSingleplayerContext singleplayer, int x) {
		singleplayer.getServer().runCommand("tp @p " + x + ".5 -60 0.5");
		singleplayer.getClientLevel().waitForChunksRender();
	}

	private static ServerPlayer serverPlayer(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static void waitForServer(ClientGameTestContext context, TestSingleplayerContext singleplayer, Predicate<MinecraftServer> condition, int maxTicks) {
		for (int tick = 0; tick < maxTicks; tick++) {
			if (singleplayer.getServer().computeOnServer(condition::test)) {
				return;
			}

			context.waitTick();
		}

		throw new AssertionError("Timed out waiting for the server");
	}

	private static Creeper spawnCreeperFacingPlayer(ClientGameTestContext context, TestSingleplayerContext singleplayer, int dx, int dz, boolean noAi) {
		Creeper creeper = singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = serverPlayer(server);
			ServerLevel level = server.overworld();
			double x = Mth.floor(player.getX()) + 0.5 + dx;
			double z = Mth.floor(player.getZ()) + 0.5 + dz;
			float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * Mth.RAD_TO_DEG) - 90.0F;
			Creeper c = new Creeper(EntityType.CREEPER, level);
			c.snapTo(x, player.getY(), z, yaw, 0.0F);
			c.setYHeadRot(yaw);
			c.setYBodyRot(yaw);
			c.setNoAi(noAi);
			c.setPersistenceRequired();
			check(level.addFreshEntity(c), "Could not spawn a creeper");
			return c;
		});
		context.waitFor(client -> client.level.getEntity(creeper.getUUID()) != null);
		return creeper;
	}

	/** Puts this in the player's main hand and gives the inventory sync a moment to reach the client. */
	private static void holdInMainHand(ClientGameTestContext context, TestSingleplayerContext singleplayer, ItemStack stack) {
		singleplayer.getServer().runOnServer(server -> serverPlayer(server).setItemInHand(InteractionHand.MAIN_HAND, stack.copy()));
		context.waitTicks(5);
	}

	/** A right-click on the creeper with what's in hand opens no panel and starts no bomb timer. */
	private static void rightClickDoesNothing(ClientGameTestContext context, TestSingleplayerContext singleplayer, Creeper creeper, String why) {
		lookAt(context, creeper);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitTicks(5);
		check(context.computeOnClient(client -> client.screen == null), why + ": no panel should open");
		check(!singleplayer.getServer().computeOnServer(_ -> creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION)), why + ": no bomb timer should start");
	}

	private static void lookAt(ClientGameTestContext context, Creeper creeper) {
		// 26.1's TestInput has no lookAt(BlockPos): aim the camera at the creeper's head ourselves.
		Vec3 target = new Vec3(creeper.getX(), creeper.getY() + 1.5, creeper.getZ());
		context.runOnClient(client -> {
			Vec3 delta = target.subtract(client.player.getEyePosition());
			float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
			float pitch = (float) -(Mth.atan2(delta.y, delta.horizontalDistance()) * Mth.RAD_TO_DEG);
			client.player.setYRot(yaw);
			client.player.setXRot(pitch);
		});
		context.waitTick();
	}

	private static void openPanel(ClientGameTestContext context, TestSingleplayerContext singleplayer, Creeper creeper) {
		lookAt(context, creeper);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitForScreen(WireCutScreen.class);
		check(singleplayer.getServer().computeOnServer(server -> creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION)), "Right-click should start a defuse session");
	}

	/** Ends a test bomb without a bang: Esc would walk away from it, and that blows it up. */
	private static void removeAndClosePanel(ClientGameTestContext context, TestSingleplayerContext singleplayer, Creeper creeper) {
		singleplayer.getServer().runOnServer(server -> creeper.discard());
		context.waitForScreen(null);
	}

	private static void hoverWire(ClientGameTestContext context, int wire) {
		Vector2d cursor = context.computeOnClient(client -> {
			Vector2i gui = ((WireCutScreen) client.screen).wireCenter(wire);
			Window window = client.getWindow();
			return new Vector2d(
					gui.x * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
					gui.y * (double) window.getScreenHeight() / window.getGuiScaledHeight());
		});
		context.getInput().setCursorPos(cursor.x, cursor.y);
		context.waitTick();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
