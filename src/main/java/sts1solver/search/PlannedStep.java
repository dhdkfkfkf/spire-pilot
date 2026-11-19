package sts1solver.search;

/** Immutable action intent plus the state-transition evidence captured for that action. */
public final class PlannedStep {
    public final ActionIntent intent;
    public final ExpectedTransition expected;

    public PlannedStep(ActionIntent intent, ExpectedTransition expected) {
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        if (expected == null) {
            throw new IllegalArgumentException("expected transition must not be null");
        }
        this.intent = intent;
        this.expected = expected;
    }

    public PlannedStep(Action action, ExpectedTransition expected) {
        this(new ActionIntent(action), expected);
    }
}
