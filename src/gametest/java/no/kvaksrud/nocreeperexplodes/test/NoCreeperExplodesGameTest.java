package no.kvaksrud.nocreeperexplodes.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodesConfig;
import no.kvaksrud.nocreeperexplodes.network.CutWirePayload;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Plays the mod on a headless test server, with mock players doing the right-clicks and wire cuts: the settings file,
 * too far, a longer defuse distance, a frozen creeper, a second player, a defuser who leaves the game or dies, defuse,
 * try to relight, defuse twice, save and reload, the blasts for a wrong wire and for running out of time, walking away
 * with Esc or by getting out of reach, and a smaller blast setting. There are no client game tests on 1.21.1, so the
 * panel, the beeps and the tear are left out.
 *
 * <p>Tests in the same batch run side by side, so each blast gets a batch of its own (they reach well past the
 * neighbouring tests), and settings that must hold for more than a tick are changed only in a batch of their own.
 */
@SuppressWarnings("removal") // makeMockServerPlayerInLevel: still the only way to get a ServerPlayer into a 1.21.1 test
public class NoCreeperExplodesGameTest implements FabricGameTest {
	private static final NoCreeperExplodesConfig DEFAULTS = NoCreeperExplodesConfig.DEFAULTS;
	private static final NoCreeperExplodesConfig ALWAYS_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 100.0, DEFAULTS.explosionSizeMultiplier());
	private static final NoCreeperExplodesConfig NEVER_SAFE = new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 0.0, DEFAULTS.explosionSizeMultiplier());

	/**
	 * Middle of the empty 8x8 test structure. It is all air with no floor, so this is the layer under it, standing on the
	 * stone platform the test framework lays under every test (at y -1 here, level with the ground around it).
	 */
	private static final BlockPos MIDDLE = new BlockPos(4, 0, 4);
	/** How far the dirt floor for a blast reaches from the creeper: past the spots 6 blocks out that the craters are checked at. */
	private static final int FLOOR_RADIUS = 7;
	/** Waiting time for a test with a bomb timer in it: the 3-second walk-away fuse, and plenty of margin. */
	private static final int BOMB_TEST_TICKS = 200;

	@GameTest(template = EMPTY_STRUCTURE)
	public void settingsFileHoldsTheDefaults(GameTestHelper helper) {
		Path configFile = FabricLoader.getInstance().getConfigDir().resolve(NoCreeperExplodesConfig.FILE_NAME);
		check(Files.isRegularFile(configFile), "Expected the settings file at " + configFile);
		check(NoCreeperExplodesConfig.load(configFile).equals(DEFAULTS), "Expected the settings file to hold the defaults, got " + NoCreeperExplodesConfig.load(configFile));
		check(NoCreeperExplodes.config().equals(DEFAULTS), "Expected the default settings, got " + NoCreeperExplodes.config());
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "nocreeperexplodes_defuse_distance")
	public void tooFarUnlessTheDistanceAllowsIt(GameTestHelper helper) {
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -3);

		// More than 1 block of space between them: nothing starts.
		rightClick(player, creeper);
		check(!creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "No defuse session from 3 blocks away");

		// With a 3-block defuse distance it does, and the bomb timer measures against that distance too.
		NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(3.0, DEFAULTS.successChancePercent(), DEFAULTS.explosionSizeMultiplier()));
		rightClick(player, creeper);
		DefuseSession started = creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
		check(started != null && started.player().equals(player.getUUID()), "A 3-block defuse distance should start a defuse session 3 blocks away");

		helper.startSequence()
				.thenExecuteAfter(10, () -> {
					check(started.equals(creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION)), "3 blocks away is not walking away with a 3-block defuse distance");
					removeBomb(creeper);
					NoCreeperExplodes.setConfig(DEFAULTS);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void creeperHoldsStillForItsOneDefuser(GameTestHelper helper) {
		Creeper creeper = spawnCreeper(helper, false);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		DefuseSession started = creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
		check(started != null && started.player().equals(player.getUUID()), "Right-click should start a defuse session");
		Vec3[] heldAt = new Vec3[1];

		helper.startSequence()
				.thenExecuteAfter(5, () -> {
					NoCreeperExplodes.tryStartDefuse(FakePlayer.get(helper.getLevel()), creeper);
					check(started.equals(creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION)), "A second player must not take over the defuse");
					// A laggy double click from the same player must not restart (or walk away from) the defuse.
					rightClick(player, creeper);
					check(started.equals(creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION)), "A repeated click must not end or restart the running defuse");

					Vec3 elsewhere = helper.absoluteVec(Vec3.atBottomCenterOf(MIDDLE.west(4)));
					check(creeper.getNavigation().moveTo(elsewhere.x, elsewhere.y, elsewhere.z, 1.0), "The creeper should have somewhere to walk to");
					heldAt[0] = creeper.position();
				})
				.thenExecuteAfter(40, () -> {
					check(creeper.position().distanceTo(heldAt[0]) < 0.05, "The creeper must not move while being defused");
					check(started.equals(creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION)), "The defuse should still be running");
					removeBomb(creeper);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void defuserLeavingTheGameReleasesTheCreeper(GameTestHelper helper) {
		Creeper creeper = spawnCreeper(helper, true);
		creeper.setAttached(NoCreeperExplodes.DEFUSE_SESSION, DefuseSession.start(UUID.randomUUID(), helper.getLevel().getGameTime()));

		helper.startSequence()
				.thenExecuteAfter(1, () -> {
					check(!creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "A defuser who left the game should release the creeper on its next tick");
					check(creeper.isAlive() && !NoCreeperExplodes.isHeld(creeper), "A released creeper is an ordinary creeper again");
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void defuserDyingReleasesTheCreeper(GameTestHelper helper) {
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");
		player.kill();
		check(!player.isAlive(), "The defuser should be dead");

		helper.startSequence()
				.thenExecuteAfter(1, () -> {
					check(!creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Dying should release the creeper, not walk away from it");
					// The panel closes on death, and that reaches the server too: still no bang.
					NoCreeperExplodes.cutWire(player, creeper, CutWirePayload.CANCEL);
					check(!creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "The closing panel of a dead defuser must not set the creeper off");
				})
				.thenExecuteAfter(1, () -> check(creeper.isAlive() && !NoCreeperExplodes.isHeld(creeper), "Dying should leave an ordinary creeper"))
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = BOMB_TEST_TICKS)
	public void safeWireDefusesForGood(GameTestHelper helper) {
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");

		// With a 100% chance any wire defuses it. Changed and restored within the tick, so the other tests never see it.
		NoCreeperExplodes.setConfig(ALWAYS_SAFE);
		NoCreeperExplodes.cutWire(player, creeper, 1);
		NoCreeperExplodes.setConfig(DEFAULTS);
		check(creeper.isAlive() && NoCreeperExplodes.isDefused(creeper), "A safe cut should defuse the creeper");
		check(!creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "A defused creeper's bomb timer should be gone");

		// A defused creeper can't be lit any more, by flint and steel or otherwise. In survival, so a use would cost durability.
		player.setGameMode(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
		rightClick(player, creeper);
		check(!creeper.isIgnited(), "Flint and steel should not ignite a defused creeper");
		check(player.getMainHandItem().getDamageValue() == 0, "Flint and steel should not lose durability on a defused creeper");
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		creeper.ignite();
		creeper.setSwellDir(1);

		helper.startSequence()
				.thenExecuteFor(60, () -> {
					check(creeper.isAlive(), "A defused creeper must never explode");
					check(creeper.getSwellDir() < 0 && creeper.getSwelling(1.0F) <= 0.0F, "A defused creeper must not swell");
				})
				.thenExecute(() -> {
					check(creeper.isAlive() && !creeper.isIgnited(), "A defused creeper must never explode");

					// ...and it can't be defused a second time.
					rightClick(player, creeper);
					check(!creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "A defused creeper must not start a new defuse session");
					check(NoCreeperExplodes.isDefused(creeper), "A defused creeper stays defused");
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void defusedSurvivesSaveAndReload(GameTestHelper helper) {
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");

		Creeper ticking = reload(creeper);
		check(!ticking.hasAttached(NoCreeperExplodes.DEFUSE_SESSION) && !NoCreeperExplodes.isDefused(ticking), "A bomb timer is never saved with the creeper");

		NoCreeperExplodes.setConfig(ALWAYS_SAFE);
		NoCreeperExplodes.cutWire(player, creeper, 2);
		NoCreeperExplodes.setConfig(DEFAULTS);
		check(NoCreeperExplodes.isDefused(creeper), "A safe cut should defuse the creeper");

		Creeper reloaded = reload(creeper);
		check(NoCreeperExplodes.isDefused(reloaded) && NoCreeperExplodes.isHeld(reloaded), "Defused should survive a save and reload");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "nocreeperexplodes_blast_wrong_wire")
	public void wrongWireBlowsUpFiveTimesBigger(GameTestHelper helper) {
		layDirtFloor(helper);
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");

		// With a 0% chance the wire blows it up on the spot, 5x bigger than normal.
		NoCreeperExplodes.setConfig(NEVER_SAFE);
		NoCreeperExplodes.cutWire(player, creeper, 0);
		NoCreeperExplodes.setConfig(DEFAULTS);
		check(creeper.isRemoved(), "A wrong wire should blow the creeper up");
		checkCrater(helper, true);
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "nocreeperexplodes_blast_out_of_time", timeoutTicks = BOMB_TEST_TICKS)
	public void runningOutOfTimeBlowsUp(GameTestHelper helper) {
		layDirtFloor(helper);
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		DefuseSession session = creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
		check(session != null, "Right-click should start a defuse session");

		// Fast-forward: one second left on the clock.
		long expiresAt = helper.getLevel().getGameTime() + 20;
		creeper.setAttached(NoCreeperExplodes.DEFUSE_SESSION, new DefuseSession(session.player(), expiresAt, false));

		helper.startSequence()
				.thenWaitUntil(() -> check(creeper.isRemoved(), "Waiting for the timer to run out"))
				.thenExecute(() -> {
					long now = helper.getLevel().getGameTime();
					check(now >= expiresAt && now <= expiresAt + 2, "Should go off when the timer runs out, went off " + (now - expiresAt) + " ticks after");
					checkCrater(helper, true);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "nocreeperexplodes_blast_esc", timeoutTicks = BOMB_TEST_TICKS)
	public void escWalksAwayFromTheBomb(GameTestHelper helper) {
		layDirtFloor(helper);
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		ServerPlayer bystander = spawnPlayer(helper, 0, 1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");
		long[] walkedAwayAt = new long[1];

		helper.startSequence()
				.thenExecuteAfter(1, () -> {
					// Esc closes the panel, and the panel tells the server.
					NoCreeperExplodes.cutWire(player, creeper, CutWirePayload.CANCEL);
					walkedAwayAt[0] = helper.getLevel().getGameTime();
					DefuseSession session = creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
					check(session != null && session.walkedAway(), "Esc should walk away from the bomb, not defuse or release it");
					check(session.expiresAt() - walkedAwayAt[0] <= DefuseSession.WALK_AWAY_TICKS, "Walking away should leave at most 3 seconds, got " + (session.expiresAt() - walkedAwayAt[0]) + " ticks");
					check(creeper.isAlive(), "Walking away gives a moment to run");

					// No way back: not for the defuser, and not for anyone else, even once the defuser is gone.
					rightClick(player, creeper);
					player.kill();
					rightClick(bystander, creeper);
					check(session.equals(creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION)), "An armed bomb can't be defused again");
				})
				.thenWaitUntil(() -> check(creeper.isRemoved(), "Waiting for the bomb to go off"))
				.thenExecute(() -> {
					long took = helper.getLevel().getGameTime() - walkedAwayAt[0];
					check(took <= DefuseSession.WALK_AWAY_TICKS + 2, "Should go off within 3 seconds of walking away, took " + took + " ticks");
					checkCrater(helper, true);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "nocreeperexplodes_blast_out_of_reach", timeoutTicks = BOMB_TEST_TICKS)
	public void gettingOutOfReachWalksAway(GameTestHelper helper) {
		layDirtFloor(helper);
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");
		long[] pushedAt = new long[1];

		helper.startSequence()
				.thenExecuteAfter(1, () -> {
					// Pushed 3 blocks back: well past the defuse distance and its half block of slack.
					movePlayer(helper, player, 0, -4);
					pushedAt[0] = helper.getLevel().getGameTime();
				})
				.thenExecuteAfter(1, () -> {
					DefuseSession session = creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION);
					check(session != null && session.walkedAway(), "Getting out of reach should walk away from the bomb on the next tick");
					// The panel closes as well and tells the server; the bomb is armed already.
					NoCreeperExplodes.cutWire(player, creeper, CutWirePayload.CANCEL);
					check(session.equals(creeper.getAttached(NoCreeperExplodes.DEFUSE_SESSION)), "The closing panel must not change an armed bomb");
				})
				.thenWaitUntil(() -> check(creeper.isRemoved(), "Waiting for the bomb to go off"))
				.thenExecute(() -> {
					long took = helper.getLevel().getGameTime() - pushedAt[0];
					check(took <= DefuseSession.WALK_AWAY_TICKS + 2, "Should go off within 3 seconds of getting out of reach, took " + took + " ticks");
					checkCrater(helper, true);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = "nocreeperexplodes_blast_normal_size")
	public void blastSizeIsASetting(GameTestHelper helper) {
		layDirtFloor(helper);
		Creeper creeper = spawnCreeper(helper, true);
		ServerPlayer player = spawnPlayer(helper, 0, -1);
		rightClick(player, creeper);
		check(creeper.hasAttached(NoCreeperExplodes.DEFUSE_SESSION), "Right-click should start a defuse session");

		// At 1x a failed defuse is an ordinary creeper blast.
		NoCreeperExplodes.setConfig(new NoCreeperExplodesConfig(DEFAULTS.defuseDistance(), 0.0, 1.0));
		NoCreeperExplodes.cutWire(player, creeper, 0);
		NoCreeperExplodes.setConfig(DEFAULTS);
		check(creeper.isRemoved(), "A wrong wire should blow the creeper up");
		checkCrater(helper, false);
		helper.succeed();
	}

	/** A creeper in the middle of the test structure. Without AI it stays put, but it still ticks (and so does its bomb timer). */
	private static Creeper spawnCreeper(GameTestHelper helper, boolean noAi) {
		Creeper creeper = helper.spawn(EntityType.CREEPER, MIDDLE);
		creeper.setNoAi(noAi);
		return creeper;
	}

	/** A player in the game (online, as far as the server knows) standing this many blocks east and south of the creeper's spot. */
	private static ServerPlayer spawnPlayer(GameTestHelper helper, int dx, int dz) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		movePlayer(helper, player, dx, dz);
		return player;
	}

	private static void movePlayer(GameTestHelper helper, ServerPlayer player, int dx, int dz) {
		Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(MIDDLE.offset(dx, 0, dz)));
		player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
	}

	/** A right-click with the main hand, handled the way the server handles one from a real client. */
	private static void rightClick(ServerPlayer player, Creeper creeper) {
		player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(creeper, false, InteractionHand.MAIN_HAND));
	}

	/** Ends a test bomb without a bang: walking away from it would set it off. */
	private static void removeBomb(Creeper creeper) {
		creeper.discard();
	}

	/** Saves the creeper the way an unloading chunk does, and loads that into a fresh creeper. */
	private static Creeper reload(Creeper creeper) {
		Creeper copy = new Creeper(EntityType.CREEPER, creeper.level());
		copy.load(creeper.saveWithoutId(new CompoundTag()));
		return copy;
	}

	/**
	 * Dirt under the creeper and 7 blocks out each way, like the ground of a superflat world. The test platform is
	 * stone, which even a 5x blast barely dents.
	 */
	private static void layDirtFloor(GameTestHelper helper) {
		BlockPos ground = MIDDLE.below();

		for (BlockPos pos : BlockPos.betweenClosed(ground.offset(-FLOOR_RADIUS, 0, -FLOOR_RADIUS), ground.offset(FLOOR_RADIUS, 0, FLOOR_RADIUS))) {
			BlockState state = helper.getBlockState(pos);

			// The platform, the superflat grass around it, or a crater from an earlier test's blast.
			if (state.isAir() || state.is(Blocks.STONE) || state.is(Blocks.GRASS_BLOCK)) {
				helper.setBlock(pos, Blocks.DIRT);
			}
		}

		for (BlockPos pos : craterSpots(ground)) {
			check(helper.getBlockState(pos).is(Blocks.DIRT), "Expected dirt at " + pos + " before the blast");
		}

		check(helper.getBlockState(ground).is(Blocks.DIRT), "Expected dirt under the creeper before the blast");
	}

	/**
	 * The blast always takes out the ground under the creeper. A normal creeper blast can't reach ground 6 blocks
	 * away; a 5x one tears straight through it.
	 */
	private static void checkCrater(GameTestHelper helper, boolean big) {
		BlockPos ground = MIDDLE.below();
		check(helper.getBlockState(ground).isAir(), "Expected the blast to take out the ground under the creeper");
		int destroyed = 0;

		for (BlockPos pos : craterSpots(ground)) {
			if (helper.getBlockState(pos).isAir()) {
				destroyed++;
			}
		}

		if (big) {
			check(destroyed == 4, "Expected a 5x crater reaching 6 blocks out, but only " + destroyed + "/4 spots were blown away");
		} else {
			check(destroyed == 0, "Expected a normal-sized crater, but " + destroyed + "/4 spots 6 blocks out were blown away");
		}
	}

	private static BlockPos[] craterSpots(BlockPos ground) {
		return new BlockPos[] {ground.east(6), ground.west(6), ground.north(6), ground.south(6)};
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new GameTestAssertException(message);
		}
	}
}
