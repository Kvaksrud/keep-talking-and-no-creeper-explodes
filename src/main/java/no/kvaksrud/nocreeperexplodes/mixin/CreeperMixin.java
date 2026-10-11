package no.kvaksrud.nocreeperexplodes.mixin;

import no.kvaksrud.nocreeperexplodes.DefusableCreeper;
import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.HoldStillWhileDefusingGoal;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

@Mixin(Creeper.class)
public abstract class CreeperMixin extends Monster implements DefusableCreeper {
	@Unique
	private boolean nocreeperexplodes$failedDefuse;
	@Unique
	private long nocreeperexplodes$nextBeep;

	protected CreeperMixin(EntityType<? extends Monster> type, Level level) {
		super(type, level);
	}

	@Shadow
	private void explodeCreeper() {
		throw new AssertionError();
	}

	@Inject(method = "registerGoals", at = @At("TAIL"))
	private void nocreeperexplodes$addHoldStillGoal(CallbackInfo ci) {
		this.goalSelector.addGoal(0, new HoldStillWhileDefusingGoal((Creeper) (Object) this));
	}

	/**
	 * Every path that starts the fuse (SwellGoal, being ignited) goes through setSwellDir(1),
	 * so forcing -1 here stops the hiss and the white flash and lets an existing fuse wind down.
	 */
	@ModifyVariable(method = "setSwellDir", at = @At("HEAD"), argsOnly = true)
	private int nocreeperexplodes$holdFuse(int dir) {
		return dir > 0 && NoCreeperExplodes.isHeld((Creeper) (Object) this) ? -1 : dir;
	}

	@Inject(method = "ignite", at = @At("HEAD"), cancellable = true)
	private void nocreeperexplodes$cannotIgnite(CallbackInfo ci) {
		if (NoCreeperExplodes.isDefused((Creeper) (Object) this)) {
			ci.cancel();
		}
	}

	/** Safety net for anything that builds up swell without going through setSwellDir, like fall damage. */
	@Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
	private void nocreeperexplodes$cannotExplode(CallbackInfo ci) {
		if (!this.nocreeperexplodes$failedDefuse && NoCreeperExplodes.isHeld((Creeper) (Object) this)) {
			ci.cancel();
		}
	}

	@ModifyArg(
			method = "explodeCreeper",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)V"),
			index = 4)
	private float nocreeperexplodes$failedDefuseBoom(float radius) {
		return this.nocreeperexplodes$failedDefuse ? radius * NoCreeperExplodes.FAILED_DEFUSE_EXPLOSION_MULTIPLIER : radius;
	}

	/**
	 * Runs the bomb timer: beeps (from the creeper, so everyone nearby hears it) faster and faster,
	 * and blows up when time runs out. If the defuser dies, leaves or is pushed away, the creeper is let go.
	 */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void nocreeperexplodes$bombTimer(CallbackInfo ci) {
		if (this.level().isClientSide()) {
			return;
		}

		DefuseSession session = this.getAttached(NoCreeperExplodes.DEFUSE_SESSION);

		if (session == null) {
			this.nocreeperexplodes$nextBeep = 0L;
			return;
		}

		if (NoCreeperExplodes.defuserStillThere((Creeper) (Object) this, session) == null) {
			this.removeAttached(NoCreeperExplodes.DEFUSE_SESSION);
			this.nocreeperexplodes$nextBeep = 0L;
			return;
		}

		long now = this.level().getGameTime();

		if (!session.isActive(now)) {
			this.removeAttached(NoCreeperExplodes.DEFUSE_SESSION);
			this.nocreeperexplodes$explodeFailedDefuse();
			ci.cancel();
			return;
		}

		if (now >= this.nocreeperexplodes$nextBeep) {
			this.level().playSound(null, this.getX(), this.getEyeY(), this.getZ(), NoCreeperExplodes.DEFUSE_BEEP, this.getSoundSource(), 1.0F, 1.0F);
			this.nocreeperexplodes$nextBeep = now + NoCreeperExplodes.beepInterval(session.expiresAt() - now);
		}
	}

	@Override
	public void nocreeperexplodes$explodeFailedDefuse() {
		this.nocreeperexplodes$failedDefuse = true;
		this.explodeCreeper();
	}
}
