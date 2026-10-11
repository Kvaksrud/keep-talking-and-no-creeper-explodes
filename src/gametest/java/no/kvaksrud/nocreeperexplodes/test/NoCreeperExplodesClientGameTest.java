package no.kvaksrud.nocreeperexplodes.test;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.client.WireCutScreen;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.MixinEnvironment;

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

/**
 * Plays the mod in a real client: too far, cancel, a frozen beeping creeper, a second player, defuse,
 * try to relight, defuse twice, reload, and the 5x blasts for a wrong wire and for running out of time.
 * Screenshots land in build/run/clientGameTest/screenshots (prefixed nce_).
 */
@SuppressWarnings("UnstableApiUsage")
public class NoCreeperExplodesClientGameTest implements FabricClientGameTest {
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

			// More than 1 block away: nothing starts.
			Creeper distant = spawnCreeperFacingPlayer(context, singleplayer, 0, 3, true);
			lookAt(context, distant);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(5);
			check(context.computeOnClient(client -> client.screen == null), "No panel should open from 3 blocks away");
			singleplayer.getServer().runOnServer(server -> {
				check(!distant.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "No defuse session from 3 blocks away");
				distant.discard();
			});

			// Esc walks away from the bomb: the session ends and nothing else happens.
			Creeper walkedAway = spawnCreeperFacingPlayer(context, singleplayer, 1, 0, true);
			openPanel(context, singleplayer, walkedAway);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(null);
			context.waitTicks(5);
			singleplayer.getServer().runOnServer(server -> {
				check(!walkedAway.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Esc should end the defuse session");
				check(walkedAway.isAlive() && !NoCreeperExplodes.isDefused(walkedAway), "Esc should leave the creeper untouched");
				walkedAway.discard();
			});

			// While someone is defusing, the creeper is frozen, it beeps, and nobody else can take over.
			Creeper frozen = spawnCreeperFacingPlayer(context, singleplayer, -1, 0, false);
			beeps.set(0);
			openPanel(context, singleplayer, frozen);
			Vec3 frozenAt = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = serverPlayer(server);
				NoCreeperExplodes.tryStartDefuse(FakePlayer.get(server.overworld()), frozen);
				DefuseSession session = frozen.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				check(session != null && session.player().equals(player.getUUID()), "A second player must not take over the defuse");
				// A laggy double click from the same player must not reopen (and so cancel) the panel.
				NoCreeperExplodes.tryStartDefuse(player, frozen);
				frozen.getNavigation().moveTo(frozen.getX() - 8, frozen.getY(), frozen.getZ(), 1.0);
				return frozen.position();
			});
			context.waitTicks(40);
			context.takeScreenshot("nce_00_beeping_panel");
			singleplayer.getServer().runOnServer(server -> {
				check(frozen.position().distanceTo(frozenAt) < 0.05, "The creeper must not move while being defused");
				check(frozen.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "A repeated click must not cancel the running defuse");
			});
			check(context.computeOnClient(client -> client.screen instanceof WireCutScreen), "The panel should still be open");
			check(beeps.get() >= 2, "Expected the bomb to beep while defusing, heard " + beeps.get());
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(null);
			singleplayer.getServer().runOnServer(server -> frozen.discard());

			// The right wire defuses it.
			Creeper lucky = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			luckyId = lucky.getUUID();
			context.takeScreenshot("nce_01_creeper_before");
			openPanel(context, singleplayer, lucky);
			context.takeScreenshot("nce_02_wire_panel");
			int correct = correctWire(singleplayer, lucky);
			hoverWire(context, (correct + 1) % NoCreeperExplodes.WIRE_COUNT);
			context.takeScreenshot("nce_03_wire_hover");
			hoverWire(context, correct);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTick();
			context.takeScreenshot("nce_04_wire_snip");
			context.waitFor(client -> client.level.getEntity(luckyId) instanceof Creeper c && NoCreeperExplodes.isDefused(c));
			context.waitTicks(2);
			context.takeScreenshot("nce_05_defused_panel");
			context.waitForScreen(null);
			singleplayer.getServer().runOnServer(server -> check(lucky.isAlive() && NoCreeperExplodes.isDefused(lucky), "Right wire should defuse the creeper"));
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
			// Let the "already defused" action-bar message fade so it doesn't show up in later screenshots.
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

			// The wrong wire blows it up, 5x bigger than normal. Done last, since it leaves a big crater.
			Creeper unlucky = spawnCreeperFacingPlayer(context, singleplayer, 0, -1, true);
			BlockPos ground = BlockPos.containing(unlucky.position()).below();
			openPanel(context, singleplayer, unlucky);
			hoverWire(context, (correctWire(singleplayer, unlucky) + 1) % NoCreeperExplodes.WIRE_COUNT);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			waitForServer(context, singleplayer, server -> unlucky.isRemoved(), 40);
			context.waitForScreen(null);
			checkBigCrater(singleplayer, ground);
			context.waitTicks(10);
			context.takeScreenshot("nce_08_crater");

			// Out of time: just as big a bang. Move to fresh ground first, away from the first crater.
			singleplayer.getServer().runCommand("tp @p 60.5 -60 0.5");
			singleplayer.getClientLevel().waitForChunksRender();
			Creeper tooSlow = spawnCreeperFacingPlayer(context, singleplayer, 0, 1, true);
			BlockPos tooSlowGround = BlockPos.containing(tooSlow.position()).below();
			openPanel(context, singleplayer, tooSlow);
			singleplayer.getServer().runOnServer(server -> {
				// Fast-forward: one second left on the clock.
				DefuseSession session = tooSlow.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
				tooSlow.setAttached(NoCreeperExplodes.DEFUSE_SESSION, new DefuseSession(session.player(), session.correctWire(), tooSlow.level().getGameTime() + 20));
			});
			waitForServer(context, singleplayer, server -> tooSlow.isRemoved(), 60);
			context.waitForScreen(null);
			checkBigCrater(singleplayer, tooSlowGround);
		}

		context.runOnClient(client -> client.getSoundManager().removeListener(beepCounter));
	}

	/** A normal creeper blast can't reach ground 6 blocks away; a 5x one tears straight through it. */
	private static void checkBigCrater(TestSingleplayerContext singleplayer, BlockPos ground) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			int destroyed = 0;

			for (BlockPos pos : new BlockPos[] {ground.east(6), ground.west(6), ground.north(6), ground.south(6)}) {
				if (level.getBlockState(pos).isAir()) {
					destroyed++;
				}
			}

			check(destroyed == 4, "Expected a 5x crater reaching 6 blocks out, but only " + destroyed + "/4 spots were blown away");
		});
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

	private static int correctWire(TestSingleplayerContext singleplayer, Creeper creeper) {
		return singleplayer.getServer().computeOnServer(server -> creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION).correctWire());
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
