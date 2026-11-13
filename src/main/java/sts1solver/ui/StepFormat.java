package sts1solver.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Step-list formatting (P1-1b): repeated actions collapse into {@code 打击 ×3}.
 *
 * <p>The renderer already does this inline while walking the plan; this pure function exists so the
 * rule itself has a gate. A formatting regression in the panel is otherwise invisible to the suite,
 * and the panel is what the player reads.
 */
public final class StepFormat {

    private StepFormat() {
    }

    /** Collapses consecutive identical labels: [a,a,a,b] -> ["a x3", "b"]. */
    public static List<String> collapse(List<String> labels) {
        List<String> out = new ArrayList<String>();
        if (labels == null) {
            return out;
        }
        int i = 0;
        while (i < labels.size()) {
            String label = labels.get(i);
            int n = 1;
            while (i + n < labels.size() && labels.get(i + n).equals(label)) {
                n++;
            }
            out.add(n > 1 ? label + " ×" + n : label);
            i += n;
        }
        return out;
    }
}