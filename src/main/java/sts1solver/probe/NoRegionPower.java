package sts1solver.probe;

import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.powers.AbstractPower;

/**
 * Fixture for S306: a power with NO atlas region.
 *
 * <p>This is the shape a mod power has when {@code loadRegion(name)} finds no {@code 128/name} in
 * the power atlas -- or when the power never calls loadRegion at all. {@code FlashPowerEffect}
 * dereferences the null region, so before 1.7.5 applying such a power inside a sandbox killed the
 * search branch. Never used by production code.
 */
public final class NoRegionPower extends AbstractPower {

    public NoRegionPower(AbstractCreature owner) {
        this.name = "no region";
        this.ID = "ProbeNoRegion";
        this.owner = owner;
        this.amount = 1;
        this.type = PowerType.BUFF;
        this.isTurnBased = false;
        updateDescription();
    }
}
