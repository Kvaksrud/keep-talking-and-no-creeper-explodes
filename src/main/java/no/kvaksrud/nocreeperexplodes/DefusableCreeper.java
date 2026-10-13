package no.kvaksrud.nocreeperexplodes;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;

/** Added to {@link net.minecraft.world.entity.monster.Creeper} by CreeperMixin. */
public interface DefusableCreeper {
	/**
	 * Wrong wire, out of time, or walked away: explodes right away,
	 * {@link NoCreeperExplodesConfig#explosionSizeMultiplier()} times bigger than normal.
	 */
	void nocreeperexplodes$explodeFailedDefuse();

	/**
	 * The defuser left the bomb with time on the clock: it beeps frantically and goes off within
	 * {@link DefuseSession#WALK_AWAY_TICKS}. Tells the player (if given) to run.
	 */
	void nocreeperexplodes$walkAway(@Nullable ServerPlayer player);
}
