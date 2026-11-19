package sts1solver.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, value-only copy of every field carried by an Action.
 *
 * <p>Action is intentionally mutable while the solver annotates turn boundaries, screen answers,
 * and automatic plays. A planned step must not retain that mutable object: capture it after expansion
 * has finished annotating the action.
 */
public final class ActionIntent {
    public final int handIndex;
    public final int targetIndex;
    public final int energyOnUse;
    public final int potionSlot;
    public final String label;
    public final String cardName;
    public final String targetName;
    public final String kind;
    public final String choice;
    public final String cardId;
    public final String cardUuid;
    public final String choiceDisplay;
    public final String choiceUuids;
    public final String cardPrint;
    public final String choicePrints;
    public final String actionClass;
    public final int turnBefore;
    public final int turnAfter;
    public final boolean escaped;
    public final boolean rngNormalized;
    public final List<String> autoPlays;
    public final String screenKinds;
    public final String screenKind;
    public final String moved;
    public final String movedUuids;
    public final String movedDisplay;

    public ActionIntent(Action action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        this.handIndex = action.handIndex;
        this.targetIndex = action.targetIndex;
        this.energyOnUse = action.energyOnUse;
        this.potionSlot = action.potionSlot;
        this.label = safe(action.label);
        this.cardName = safe(action.cardName);
        this.targetName = safe(action.targetName);
        this.kind = safe(action.kind);
        this.choice = safe(action.choice);
        this.cardId = safe(action.cardId);
        this.cardUuid = safe(action.cardUuid);
        this.choiceDisplay = safe(action.choiceDisplay);
        this.choiceUuids = safe(action.choiceUuids);
        this.cardPrint = safe(action.cardPrint);
        this.choicePrints = safe(action.choicePrints);
        this.actionClass = safe(action.actionClass);
        this.turnBefore = action.turnBefore;
        this.turnAfter = action.turnAfter;
        this.escaped = action.escaped;
        this.rngNormalized = action.rngNormalized;
        List<String> plays = action.autoPlays == null
                ? Collections.<String>emptyList() : new ArrayList<String>(action.autoPlays);
        this.autoPlays = Collections.unmodifiableList(plays);
        this.screenKinds = safe(action.screenKinds);
        this.screenKind = safe(action.screenKind);
        this.moved = safe(action.moved);
        this.movedUuids = safe(action.movedUuids);
        this.movedDisplay = safe(action.movedDisplay);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
