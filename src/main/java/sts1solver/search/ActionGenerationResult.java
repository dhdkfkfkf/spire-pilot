package sts1solver.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of enumerating the legal actions at one decision point.
 *
 * <p>An incomplete result must never be consumed as a complete candidate set. The partial list is
 * retained for diagnostics only; callers must check {@link #complete()} before expanding it.
 */
public final class ActionGenerationResult {
    public final List<Action> actions;
    public final Throwable cause;
    public final String failure;

    private final boolean complete;

    private ActionGenerationResult(List<Action> actions, boolean complete,
                                   String failure, Throwable cause) {
        List<Action> copy = actions == null
                ? new ArrayList<Action>() : new ArrayList<Action>(actions);
        this.actions = Collections.unmodifiableList(copy);
        this.complete = complete;
        this.failure = failure == null ? "" : failure;
        this.cause = cause;
    }

    public static ActionGenerationResult complete(List<Action> actions) {
        return new ActionGenerationResult(actions, true, "", null);
    }

    public static ActionGenerationResult incomplete(List<Action> partial,
                                                     String failure, Throwable cause) {
        return new ActionGenerationResult(partial, false, failure, cause);
    }

    public boolean complete() {
        return complete;
    }
}
