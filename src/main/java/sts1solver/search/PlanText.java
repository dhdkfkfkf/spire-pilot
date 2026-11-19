package sts1solver.search;

/**
 * Plain-language headline for a finished plan (B16-followup).
 *
 * <p>The old panel said "未找到胜线" for every non-win, which reads as "the solver gave up" even when
 * the search had actually PROVEN that every branch dies inside the modeled fight. Those are very
 * different messages to a player standing in front of the Slime Boss: one means "search harder", the
 * other means "this deck cannot win, here is the line that survives longest". This class owns that
 * distinction so it can be asserted without a live fight; {@link Solver.Result#reason} carries the
 * evidence ("no-live-branches" = the frontier drained, i.e. every branch ended in death).
 */
public final class PlanText {

    private PlanText() {
    }

    /**
     * Below this many expanded states a drained frontier is NOT a proof of death.
     *
     * <p>Measured live (A20 Gremlin Gang, Silent): a losing-looking position drains 279 states in
     * 1.4s, because the beam collapses to the few lines that still breathe. The player then won that
     * fight by hand, so saying "every line dies" at that size is not a fact about the game, it is a
     * fact about how little was searched. The honest wording has to carry that difference (bug #1 of
     * the four-gremlin report).
     */
    public static final int PROVEN_EXPANSIONS = 1500;

    /**
     * Headline for a finished search.
     *
     * <p>P2-8: a win no longer says just "可赢". The player's question (m06072) was "why is this
     * the best line", so the headline states how good the win is: a proven zero-damage win cannot
     * be improved, anything else is explicitly "the best within the budget" with its cost.
     */
    public static String headline(Solver.Result r) {
        // B78 (user m21889): a line that did not replay is not a plan. The printed steps would be a
        // prefix of the searched line while `win` came from the full one -- that is how the panel
        // told the player to play a card the plan itself had already exhausted.
        if (!r.planVerified) {
            return replayedPartially(r.planFailedAt, r.planFailedOn, r.plan.size());
        }
        if (r.win) {
            if (r.provenOptimal) {
                return "可赢（已证明最优：无伤、不用药）";
            }
            // A0: say WHY this is the best line -- either the whole live frontier was searched, or
            // the budget ran out with candidates still open. Those are different promises.
            StringBuilder w = new StringBuilder("可赢（");
            // B72 (user m20740): "已搜完可用路线" was a lie whenever the beam squeezed a better line
            // out and then the leftover budget never replayed it (the two-slime fight: kept=270,
            // promoted=0, 7 HP lost while the parked defensive line was never looked at). Say what
            // actually happened instead of promising an exhaustion the search did not do.
            w.append(winBest(r.frontierExhausted, r.promoted, r.deferred));
            w.append(PlanText.costText(r.hpLoss, r.potionsUsed, r.potionHpSpent));
            w.append("）");
            // The "N candidates are still open" tail only belongs on a search that stopped on a
            // LIMIT. A search that converged has nothing waiting: the beam frontier ran dry, and
            // the A3 parking lot is a bounded promotion queue, not work the next pass would pick up
            // (user m19708: the panel showed hundreds of "可再算" candidates on a finished search).
            // B180 (user m24185, option C): the tail names WHICH limit stopped the search, and when
            // the parking lot is not being replayed it says how many lines are sitting there.
            String note = candidateNote(r.cutShort, openCandidates(r), r.reason, r.deferred, r.promoted);
            if (note.length() > 0) {
                w.append(note);
            }
            String parked = parkedNote(r.frontierExhausted, r.promoted, r.deferred, r.deferredWhy);
            if (parked.length() > 0) {
                w.append(parked);
            }
            return w.toString();
        }
        // 1.0.8 (C4): the ranking stopped moving before the budget ran out -- not a lost board.
        if ("converged".equals(r.reason)) {
            return "最近几回合的尽力线" + PlanText.costText(r.hpLoss, r.potionsUsed, r.potionHpSpent)
                    + "（连续数层没有更好的线就提前停了，剩余预算不会改变建议）";
        }
        // 1.0.8 (C3): a horizon-limited search did not fail to find a win -- it did not look past the
        // horizon. Saying "未找到胜线" there reads as "this position is lost", which is a claim the
        // search never made; name the limit instead.
        if ("horizon".equals(r.reason)) {
            return "最近几回合的尽力线" + PlanText.costText(r.hpLoss, r.potionsUsed, r.potionHpSpent)
                    + "（预算不够，只推了最近几回合；调大「秒数」可继续）";
        }
        // B195 (user 2026-11-19): a search that stopped on its budget still HAS an answer -- the
        // best line it found -- and the panel prints that line right under this row. "未找到胜线"
        // next to "只往前看 1 回合" read as "there is no result", which is the one thing that was
        // not true. Only a search with no line at all (or a proven death) may say "not found".
        if (r.plan != null && !r.plan.isEmpty()) {
            String limited = PlanText.limitLine(r.reason, r.hpLoss, r.potionsUsed, r.potionHpSpent,
                    r.plan.size(), r.deepestRoute);
            if (!limited.isEmpty()) {
                return limited;
            }
        }
        // P2-11b: the whole job searched more than this one pass did, and the panel shows that
        // total. Quoting only the last pass would read as a contradiction (panel: 20602, text: 256).
        return headline(r.win, r.escaped, r.reason, r.splitStates, r.plan.size(),
                r.deepestRoute, r.layersRun, r.maxLayers, r.expansions, r.totalExpansions);
    }

    /**
     * B195: the headline for a limit stop that still holds a best line ("预算用完" / "手动停止" /
     * "到层数上限"). Empty for every other reason, so the caller can fall through to the death and
     * no-limit wording. Pure, so a gate can pin each case without building a search result.
     */
    public static String limitLine(String reason, int hpLoss, int potionsUsed, int potionHpSpent,
                                   int steps, boolean deepest) {
        String why = limitStop(reason);
        if (why.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("当前最好的线");
        b.append(PlanText.costText(hpLoss, potionsUsed, potionHpSpent));
        b.append("（").append(why).append("，没算到胜利；可以再算）");
        if (deepest && steps > 0) {
            b.append(" · 给最长生存线 ").append(steps).append(" 步");
        }
        return b.toString();
    }

    private static String limitStop(String reason) {
        if ("time".equals(reason) || "nodes".equals(reason)) {
            return "预算用完";
        }
        if ("stopped".equals(reason)) {
            return "手动停止";
        }
        if ("layers".equals(reason)) {
            return "到层数上限";
        }
        return "";
    }

    /**
     * B192 (user 2026-11-18): a plan that "only ends turns" needs to say why.
     *
     * <p>Looter (and any fleeing monster) can end the fight by leaving, not by dying: the winning
     * line is then two end turns with no card played, and the panel used to print just
     * {@code 结束回合 | 结束回合} under "可赢（已证明最优）". The player read that as the solver
     * doing nothing, and pressing 计算 again only repeated it. The search already chose this line on
     * purpose (B164 prices killing vs. letting it run); this row states the reason so the plan can be
     * trusted at a glance.
     *
     * <p>The shape that proves it: the plan's LAST step is an end turn whose turn counter did not
     * advance (the fight ended inside it) while enemies are still standing (monster HP > 0). A win
     * whose enemies are all at 0 HP ended by killing, not by leaving, and gets no row. Empty string
     * when the shape does not hold, which is every ordinary plan.
     */
    public static String leavingNote(Solver.Result r) {
        if (r == null || !r.win || !r.planVerified || r.plan == null || r.plan.isEmpty()) {
            return "";
        }
        Solver.Step last = r.plan.get(r.plan.size() - 1);
        if (last == null || last.action == null || !last.action.isEndTurn()) {
            return "";
        }
        if (last.turnBefore < 0 || last.turnAfter != last.turnBefore) {
            return "";
        }
        boolean standing = false;
        if (last.monsterHp != null) {
            for (int hp : last.monsterHp) {
                if (hp > 0) {
                    standing = true;
                    break;
                }
            }
        }
        if (!standing) {
            return "";
        }
        boolean played = false;
        for (int i = 0; i < r.plan.size(); ++i) {
            Solver.Step st = r.plan.get(i);
            if (st != null && st.action != null && !st.action.isEndTurn()) {
                played = true;
                break;
            }
        }
        return (played ? "" : "这场不用出牌：")
                + "敌人会在第 " + last.turnBefore + " 回合自己逃跑，战斗随即结束（之后不会再吃伤害）";
    }

    /**
     * B173 (user m21993/m22084): the cost of a winning line, as the panel prints it in the
     * "预算内最好" line. A potion is not free -- it is priced as HP the belt gave up
     * ({@link Solver.Result#potionHpSpent}, which the search subtracts at every node) -- and this
     * line used to print only the raw HP loss, so a line that spent nine HP on a potion read as
     * "掉血 22，用药 1 瓶" with no cost anywhere. The price and the sum are printed beside it now,
     * the same way the top line prints its own raw/credited pair.
     */
    public static String costText(int hpLoss, int potionsUsed, int potionHpSpent) {
        StringBuilder w = new StringBuilder(hpLoss > 0 ? "掉血 " + hpLoss : "无伤");
        if (potionsUsed > 0) {
            w.append("，用药 ").append(potionsUsed).append(" 瓶");
            if (potionHpSpent > 0) {
                w.append("（价值 ").append(potionHpSpent).append(" HP，合计 ")
                        .append(hpLoss + potionHpSpent).append("）");
            }
        }
        return w.toString();
    }

    /**
     * Candidates the search has not opened yet -- and only when it stopped on a limit.
     *
     * <p>A converged search has no such thing: the live frontier ran dry, so {@code frontierLeft}
     * is 0 anyway, and the A3 parking lot is beam overflow that only a bounded number of promotions
     * ever re-runs. Counting it as "still to be computed" (the old behaviour) told the player there
     * were hundreds of branches one second away from being explored when in fact nothing would look
     * at them again.
     */
    /**
     * B78: what the panel says instead of a win when the line could not be replayed.
     *
     * <p>Kept separate from {@link #headline(Solver.Result)} so a gate can assert the wording
     * without building a whole search result.
     */
    public static String replayedPartially(int failedAt, String failedOn, int stepsKept) {
        StringBuilder sb = new StringBuilder("这条线重放不通过（第 ");
        sb.append(failedAt + 1).append(" 步");
        if (failedOn != null && !failedOn.isEmpty()) {
            sb.append("：").append(failedOn);
        }
        sb.append("），只给前 ").append(stepsKept).append(" 步；不算可赢，可以再算一次");
        return sb.toString();
    }

    public static int openCandidates(Solver.Result r) {
        return r.cutShort ? r.frontierLeft : 0;
    }

    /**
     * B72: what "best" means for a winning line, given what the search did with its parked
     * candidates. A dry frontier with a non-empty parking lot that was never replayed is NOT
     * "已搜完可用路线" -- the lines the beam dropped are still unlooked-at.
     */
    public static String winBest(boolean frontierExhausted, int promoted, int deferred) {
        if (frontierExhausted && promoted == 0 && deferred > 0) {
            return "前沿跑完，但没回放被挤掉的线，最好：";
        }
        return frontierExhausted ? "已搜完可用路线，最好：" : "预算内最好：";
    }

    /**
     * B72: the tail that has to appear when the beam dropped lines and the leftover budget never
     * re-ran any of them. Empty when there is nothing parked or something was replayed.
     */
    public static String parkedNote(boolean frontierExhausted, int promoted, int deferred) {
        return parkedNote(frontierExhausted, promoted, deferred, null);
    }

    /**
     * B103 (user m03616): the same tail, saying WHY nothing was replayed.
     *
     * <p>"可以再算" was the only advice the panel gave while 2000 parked lines sat unreplayed; for
     * the shared parked-pass ceiling or the layer cap that advice is wrong (computing again hits the
     * same wall unless the cap moves), and for a failed rebuild it is not advice at all -- it is a
     * fidelity gap the player cannot fix by pressing the button again.
     */
    public static String parkedNote(boolean frontierExhausted, int promoted, int deferred, String why) {
        if (!frontierExhausted || promoted > 0 || deferred <= 0) {
            return "";
        }
        String tail = why == null || why.isEmpty() ? "可以再算" : why;
        return " 还有 " + deferred + " 条被挤掉的线没回放（" + tail + "）";
    }

    /**
     * The "N candidates are still open" tail, or "" when the search converged. Pure so a gate can
     * assert each case without building a whole {@link Solver.Result}.
     */
    public static String candidateNote(boolean cutShort, int open) {
        if (!cutShort || open <= 0) {
            return "";
        }
        return " 还有 " + open + " 条没展开（受层数/预算限制，可以再算）";
    }

    /**
     * B180 (user m24185, option C): the same tail, naming the limit that actually stopped the
     * search. Before this the panel said "受层数/预算限制" for every stop, so a player who had set
     * no time limit read it as the search still working through candidates (the 169 open positions
     * were a per-rung node budget, { user m24116 }). When the parking lot is not being replayed the
     * tail also states how many lines are parked, so the "can compute again" advice is honest about
     * what a second run would pick up.
     */
    public static String candidateNote(boolean cutShort, int open, String reason, int deferred, int promoted) {
        if (!cutShort || open <= 0) {
            return "";
        }
        boolean redo = reason == null || reason.isEmpty()
                || "nodes".equals(reason) || "time".equals(reason) || "stopped".equals(reason);
        StringBuilder w = new StringBuilder();
        w.append(" 还有 ").append(open).append(" 条没展开（").append(limitText(reason));
        w.append(redo ? "，可以再算）" : "）");
        if (deferred > 0 && promoted > 0) {
            w.append("；另有 ").append(deferred).append(" 条被挤掉的线停着（本档回放 ")
                    .append(promoted).append(" 条）");
        }
        return w.toString();
    }

    /** B180: the search's stop reason in words the panel can print. */
    public static String limitText(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "受层数/预算限制";
        }
        if ("nodes".equals(reason)) {
            return "节点预算用完";
        }
        if ("time".equals(reason)) {
            return "时间预算用完";
        }
        if ("layers".equals(reason)) {
            return "到层数上限";
        }
        if ("stopped".equals(reason)) {
            return "手动停止";
        }
        return "受层数/预算限制";
    }

    /**
     * Same text from raw fields, so a gate can assert each case.
     *
     * @param splits    how many split points the search walked through (Slime Boss and friends)
     * @param steps     length of the plan that is shown (deepest surviving line when {@code deepest})
     * @param expansions how many states the search expanded; below {@link #PROVEN_EXPANSIONS} a
     *                   drained frontier is reported as "not found", never as "all lines die"
     */
    public static String headline(boolean win, boolean escaped, String reason, int splits,
                                  int steps, boolean deepest, int layersRun, int maxLayers) {
        return headline(win, escaped, reason, splits, steps, deepest, layersRun, maxLayers,
                Integer.MAX_VALUE);
    }

    /** Same text with the search size, which decides how strongly a dead end may be worded. */
    public static String headline(boolean win, boolean escaped, String reason, int splits,
                                  int steps, boolean deepest, int layersRun, int maxLayers,
                                  int expansions) {
        return headline(win, escaped, reason, splits, steps, deepest, layersRun, maxLayers,
                expansions, expansions);
    }

    /**
     * Same text, with both the pass that produced the line and the whole job. The verdict still
     * keys off the pass alone (a portfolio's four beams are not a proof of anything on their own),
     * but the number the player reads is not allowed to be smaller than the panel's without saying
     * so.
     */
    public static String headline(boolean win, boolean escaped, String reason, int splits,
                                  int steps, boolean deepest, int layersRun, int maxLayers,
                                  int expansions, int totalExpansions) {
        if (win) {
            return "可赢";
        }
        if (escaped) {
            return "只能逃跑（烟雾弹）";
        }
        boolean drained = "no-live-branches".equals(reason);
        boolean exhausted = drained && expansions >= PROVEN_EXPANSIONS;
        boolean thin = drained && expansions < PROVEN_EXPANSIONS;
        StringBuilder b = new StringBuilder(exhausted ? "所有路线都会死" : "未找到胜线");
        if (exhausted && splits > 0) {
            b.append("（已搜索 ").append(splits).append(" 条分叉）");
        }
        if (thin) {
            // P2-11b: name both scales. The pass figure decides how strongly this may be worded
            // (R10 推论 3), the job figure explains the number the panel was showing.
            b.append("（本档只搜了 ").append(expansions).append(" 条分叉");
            if (totalExpansions > expansions && totalExpansions < Integer.MAX_VALUE) {
                b.append("，整次搜索共 ").append(totalExpansions).append(" 条");
            }
            b.append("，不能证明会死；可加大预算或再点「计算」）");
        }
        if (deepest) {
            b.append(" · 给最长生存线 ").append(steps).append(" 步");
            if (maxLayers > 0 && layersRun >= maxLayers) {
                // B43 (Hexaghost): the deepest line ended at the layer cap, not at the end of the
                // fight -- say so, or the player reads "32 steps" as "the fight is decided".
                b.append("（到深度上限，未模拟到战斗结束；可再算）");
            }
        } else if (exhausted) {
            b.append(" · 没有一条线能走完");
        } else if (!thin && maxLayers > 0 && layersRun >= maxLayers) {
            b.append("（到深度上限，未模拟到战斗结束）");
        }
        return b.toString();
    }
}
