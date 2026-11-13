package demoMod.probe;

import com.megacrit.cardcrawl.cards.AbstractCard;
import java.util.ArrayList;

/**
 * A stand-in for a mod's {@code static final} list of cards.
 *
 * <p>CaiNongMod's GetInside keeps the two cards it picked in exactly such a list
 * ({@code ModHelper.cards_Inside}) and replays them the next time the card is played. A static final
 * cannot be swapped -- JDK 21 refuses {@code Field.set} on one -- so the mirror has to rewrite the
 * contents in place and put the live picks back on uninstall. This fixture lives in this jar so gate
 * S142 can prove that path on an install where CaiNongMod is not enabled; the package is
 * {@code demoMod.*} on purpose, like {@link ModProbeCounter}.
 */
public final class ModProbeCards {

    /** The sandbox's copy of a mod's pending-card list; nothing in production reads it. */
    public static final ArrayList<AbstractCard> HELD = new ArrayList<AbstractCard>();

    private ModProbeCards() {
    }
}
