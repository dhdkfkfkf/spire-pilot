package sts1solver.search;

/**
 * Thrown when a planned line cannot be replayed in a fresh copy of the fight.
 *
 * <p>B78 (user m21789): the search explores one tree and the plan is later replayed on a fresh
 * clone, so a step that only names a POSITION ("hand index 7") or a card ID without its upgrade
 * state can silently resolve to a different card. That used to produce a plan whose printed steps
 * and whose verified steps were two different games: the panel said 可赢 from the explored line
 * while the replay had died early, and autoplay then "played the card next to the intended one".
 * A replay that cannot match the card the plan named is a fact about the plan, not an error to
 * paper over, so it is raised instead of guessed.
 */
public class ReplayException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * B79 (user m22205-3): why the line failed, as one of a few coarse buckets, so the log can tell
     * "the model is wrong" apart from "we asked too early":
     * <ul>
     *   <li>{@code not-in-hand} -- the card the step names is not in the hand at all</li>
     *   <li>{@code not-offered} -- a select screen is open but it does not offer the planned card</li>
     *   <li>{@code not-settled} -- a screen was expected and never appeared, even after settling</li>
     * </ul>
     */
    public final String kind;

    public ReplayException(String message) {
        this("unspecified", message);
    }

    public ReplayException(String kind, String message) {
        super(message);
        this.kind = kind == null ? "unspecified" : kind;
    }
}
