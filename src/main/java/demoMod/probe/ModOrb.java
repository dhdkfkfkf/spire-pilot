package demoMod.probe;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.orbs.AbstractOrb;

/**
 * A stand-in for a mod's new mechanic in the Defect orb slot (user m11229 question 2): a brand new
 * orb type the vanilla game has never heard of.
 *
 * <p>Why this proves something: orbs are <b>player state</b> ({@code AbstractPlayer.orbs} plus
 * {@code masterMaxOrbs}), not a process-global. The sandbox clones the player object graph, so a
 * mod orb channeled inside the search lives in the clone and dies with it. This fixture has a real
 * effect on combat state (evoke bumps a mod counter and adds block) so the gate can prove that the
 * effect happens in the sandbox and leaves nothing behind.
 */
public class ModOrb extends AbstractOrb {

    public ModOrb() {
        this.ID = "DemoModOrb";
        this.name = "Demo Orb";
        this.description = "Evoke: +" + this.evokeAmount + ". #Passive #1.";
        this.basePassiveAmount = 1;
        this.passiveAmount = 1;
        this.baseEvokeAmount = 2;
        this.evokeAmount = 2;
    }

    @Override
    public void updateDescription() {
        this.description = "Evoke: +" + this.evokeAmount + ".";
    }

    @Override
    public void onEvoke() {
        ModProbeCounter.counter += this.evokeAmount;
        if (AbstractDungeon.player != null) {
            AbstractDungeon.player.addBlock(this.passiveAmount);
        }
    }

    @Override
    public AbstractOrb makeCopy() {
        return new ModOrb();
    }

    @Override
    public void render(SpriteBatch sb) {
        // nothing to draw: this fixture only exists for the state model
    }

    @Override
    public void playChannelSFX() {
        // no audio
    }
}
