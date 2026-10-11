package no.kvaksrud.nocreeperexplodes;

/** Added to {@link net.minecraft.world.entity.monster.Creeper} by CreeperMixin. */
public interface DefusableCreeper {
	/**
	 * Wrong wire or out of time: explodes right away,
	 * {@link NoCreeperExplodes#FAILED_DEFUSE_EXPLOSION_MULTIPLIER} times bigger than normal.
	 */
	void nocreeperexplodes$explodeFailedDefuse();
}
