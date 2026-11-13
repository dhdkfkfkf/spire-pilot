package sts1solver.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * One turn of the planned route: a label, the turn stats, and the actions of that turn.
 *
 * <p>STS2's overlay (src\UI\SolverRouteRow.cs) draws a turn as one card: the turn label on the
 * left, the action pills flowing in the middle, and the damage / HP / energy numbers on the right.
 * We used to glue label and stats into a single header *above* the pills, which is exactly what
 * the player saw as overlap ("每个回合的步骤和第几回合挡住了"). Splitting them into
 * {@link #label} and {@link #stats} lets the layout reserve a left column for the label and a
 * right edge for the numbers, so a pill can never be drawn on top of them.
 */
public final class RouteRow {

    /** Left column text, e.g. 第 2 回合. */
    public final String label;

    /** Right-aligned numbers, e.g. HP 55 → 50   能量 3 (may be empty). */
    public final String stats;

    /** Legacy single-string header ({@code label + "   " + stats}); kept for older callers. */
    public final String head;

    /** Pill labels, in plan order. */
    public final List<String> pills = new ArrayList<String>();

    /** Plan step index for each pill, same order as {@link #pills}. */
    public final List<Integer> steps = new ArrayList<Integer>();

    public RouteRow(String label, String stats) {
        this.label = label == null ? "" : label;
        this.stats = stats == null ? "" : stats;
        this.head = this.stats.isEmpty() ? this.label : this.label + "   " + this.stats;
    }

    /** Header-only row: the whole string becomes the label (used by the layout gates). */
    public RouteRow(String head) {
        this(head, "");
    }

    public void add(String label, int step) {
        pills.add(label == null ? "" : label);
        steps.add(step);
    }

    public int size() {
        return pills.size();
    }

    /** Step of the first pill whose step is >= from, or -1 when this row has none. */
    public int firstStepAtOrAfter(int from) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) >= from) {
                return steps.get(i);
            }
        }
        return -1;
    }
}
