package no.kvaksrud.nocreeperexplodes;

import java.util.EnumSet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;

/** Top-priority goal: while someone is cutting its wires the creeper stands still and stares at them. */
public class HoldStillWhileDefusingGoal extends Goal {
	private final Creeper creeper;

	public HoldStillWhileDefusingGoal(Creeper creeper) {
		this.creeper = creeper;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return NoCreeperExplodes.activeDefuser(this.creeper) != null;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.creeper.getNavigation().stop();
	}

	@Override
	public void tick() {
		this.creeper.getNavigation().stop();
		this.creeper.setDeltaMovement(0.0, this.creeper.getDeltaMovement().y, 0.0);
		ServerPlayer defuser = NoCreeperExplodes.activeDefuser(this.creeper);

		if (defuser != null) {
			this.creeper.getLookControl().setLookAt(defuser, 30.0F, 30.0F);
		}
	}
}
