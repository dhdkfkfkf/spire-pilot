package sts1solver.ui;

/**
 * The rotating progress subtitle (m19280, from the user's "稳定主状态 + 轮换副文案" design).
 *
 * <p>One line, composed as {@code 阶段 · 节点 N · 深度 D · 副文案}. The stage and the numbers are
 * stable -- they only change when the search really changes -- and only the trailing phrase
 * rotates, so the line never reads as flicker. The six rules the design asked for, and how each
 * one is honoured here:
 *
 * <ol>
 *   <li><b>The main state is never random.</b> Stage name and every number come from
 *       {@link sts1solver.Gameplay.Status}; nothing about them depends on a hash.</li>
 *   <li><b>The phrase rotates on a clock, not per frame.</b> {@link #WINDOW_MS} is 1.5s; inside one
 *       window the same phrase is returned, so a 60 fps panel draws one string 90 times.</li>
 *   <li><b>A long stall changes the phrase.</b> Past {@link #STALL_MS} the phrase comes from
 *       {@link #STALLED} instead of the phase table, so "no progress" never sits next to a joke.</li>
 *   <li><b>Timeout, degrade and errors win.</b> The stalled / finished / no-plan branches are
 *       checked before any rotating table is consulted.</li>
 *   <li><b>No string building on the hot path.</b> The composed line is cached on a cheap numeric
 *       key; while the key is unchanged this returns the previous string without allocating. The
 *       search loop is untouched -- it only writes counters, and this reads them once per frame.</li>
 *   <li><b>Deterministic choice.</b> The phrase index is {@code hash(phase, window)}, so the same
 *       second of the same phase always shows the same words; two players watching the same run
 *       see the same line, and a screenshot can be explained.</li>
 * </ol>
 *
 * <p>What it deliberately does NOT do: invent numbers. The design's example line carried a cache
 * hit percentage, but no cache-hit ratio reaches {@link sts1solver.Gameplay.Status} -- the search
 * logs transpositions and equivalent-merge counts, and turning those into a percentage here would
 * be a figure nobody measured. The line carries nodes, depth and rate, which are real.
 */
public final class StatusTicker {

    /** How long one phrase stays on screen. Long enough to read, short enough to feel alive. */
    private static final long WINDOW_MS = 1500L;

    /** Past this much time with no new node the search counts as stalled (matches Status.stalled). */
    private static final long STALL_MS = 3000L;

    /** Opening: nothing has been searched yet, or the first slice has not finished. */
    private static final String[] INIT = {
            "正在铺开时间线",
            "读取牌库与遗物",
            "正在给敌人建档",
            "构建初始战斗状态",
            "先把这一局看清楚",
            "正在数对面的血量",
            "准备反复去世",
    };

    /**
     * The ordinary widening search. m19340 (user "左边空间大，可以加很多有趣文本"): the table grew
     * from 12 to 26 lines. The left strip is the one place with room for character, and a line that
     * rotates every 1.5 seconds through 26 entries takes 39 seconds to repeat -- long enough that a
     * player watching a slow search does not memorise it.
     */
    private static final String[] SEARCH = {
            "枚举可达状态",
            "模拟抽牌序列",
            "计算斩杀线",
            "评估格挡收益",
            "正在翻牌库",
            "给敌人排死亡顺序",
            "寻找不挨打的未来",
            "把能量花在刀刃上",
            "正在处理分支爆炸",
            "在平行宇宙里试错",
            "和发牌员讲道理",
            "正在说服格挡值",
            "正在给心脏做心电图",
            "清点手牌里的每一分钱",
            "试着不打脸也不挨打",
            "正在给遗物记账",
            "把每一滴能量榨干",
            "检查是不是又要鬼抽",
            "在时间线里找那一条",
            "正在说服荆棘少扎两下",
            "算算这回合能不能收工",
            "给敌人安排退场顺序",
            "正在权衡省血还是省时间",
            "翻一翻还有没有别的路",
            "正在排查致命的那一手",
            "试着让这张牌值回票价",
    };

    /** Deep in the tree: many layers down, few new answers. */
    private static final String[] DEEP = {
            "进入深水区",
            "正在啃硬骨头",
            "分支还在长",
            "当前最优正在收敛",
            "正在比较两条很像的线",
            "这里已经没有捷径了",
            "正在往更深处挖",
            "树还在长，先别急",
            "正在区分运气和实力",
    };

    /** Late in the budget: the search is settling on an answer. */
    private static final String[] BACKTRACK = {
            "回溯最优路径",
            "整理出牌序列",
            "挑一条最像人的路线",
            "把失败分支折叠起来",
            "正在删掉会死的未来",
            "正在把路线压到最短",
            "准备交卷",
            "正在挑一条能复述出来的线",
            "把多余的回合还给你",
    };

    /** No search running and a line is held. */
    private static final String[] DONE = {
            "计算完成",
            "最优序列已就绪",
            "找到一条可行路线",
            "这条线能打",
            "算完了，剩下交给发牌员",
            "路线已备好，等你出牌",
    };

    /** No search and no line: the panel is idle. */
    private static final String[] IDLE = {
            "点「计算」求解本场",
            "空闲",
    };

    /** The search is alive but stopped producing nodes -- the one branch with no flavour. */
    private static final String[] STALLED = {
            "搜索停住了，可能卡在某次结算",
            "没有新节点，再点「计算」可以停",
    };

    // ---- cache: the composed line, rebuilt only when its own key changes ---------------------
    private static long lastKey = Long.MIN_VALUE;
    private static String lastText = "";

    private StatusTicker() {
    }

    /**
     * The one line the panel draws under its title. Never null, never empty; cheap while nothing
     * has changed.
     */
    public static String line(sts1solver.Gameplay.Status s) {
        if (s == null) {
            return "";
        }
        long window = System.currentTimeMillis() / WINDOW_MS;
        // Coarse buckets: a key that changed every frame would rebuild the string every frame,
        // which is exactly the hot-path cost rule 5 forbids. 100 nodes / 1 layer / 1% are the
        // resolutions a reader can actually tell apart.
        int nodeBucket = bucket(s.worldlines);
        long key = key(s, window, nodeBucket);
        if (key == lastKey) {
            return lastText;
        }
        String text = compose(s, window, nodeBucket);
        lastKey = key;
        lastText = text;
        return text;
    }

    /** Harness hook: the phase table a status maps to, so a gate can pin the branches. */
    public static String phaseName(sts1solver.Gameplay.Status s) {
        if (s == null) {
            return "idle";
        }
        if (s.paused) {
            return "paused";
        }
        if (s.stalled()) {
            return "stalled";
        }
        if (!s.searching) {
            return s.hasPlan ? "done" : "idle";
        }
        if (s.worldlines <= 0) {
            return "init";
        }
        if (s.pct >= 75) {
            return "backtrack";
        }
        if (s.layers >= DEEP_LAYERS) {
            return "deep";
        }
        return "search";
    }

    /** Layers at which the wording switches from "searching" to "deep". */
    private static final int DEEP_LAYERS = 8;

    /**
     * Harness hook: which phrase a (phase, window) pair picks, without needing a live search.
     * Deterministic, so a gate can assert the same input twice gives the same words.
     */
    public static String phrase(String phase, long window) {
        String[] table = tableFor(phase);
        if (table.length == 0) {
            return "";
        }
        return table[Math.floorMod(hash(phase, window), table.length)];
    }

    /** Harness hook: how many phrases a phase offers (0 for the phases that never rotate). */
    public static int phraseCount(String phase) {
        return tableFor(phase).length;
    }

    private static String[] tableFor(String phase) {
        if ("stalled".equals(phase)) {
            return STALLED;
        }
        if ("paused".equals(phase)) {
            return new String[]{"游戏暂停中"};
        }
        if ("init".equals(phase)) {
            return INIT;
        }
        if ("deep".equals(phase)) {
            return DEEP;
        }
        if ("backtrack".equals(phase)) {
            return BACKTRACK;
        }
        if ("done".equals(phase)) {
            return DONE;
        }
        if ("idle".equals(phase)) {
            return IDLE;
        }
        return SEARCH;
    }

    private static String compose(sts1solver.Gameplay.Status s, long window, int nodeBucket) {
        String phase = phaseName(s);
        StringBuilder b = new StringBuilder(64);
        b.append(stageWord(s, phase));
        if (s.searching || s.hasPlan) {
            b.append(" · 节点 ").append(nodeText(s.worldlines));
            if (s.layers > 0) {
                b.append(" · 深度 ").append(s.layers);
            }
            long rate = s.worldlinesPerSecond;
            if (rate > 0) {
                b.append(" · ").append(rate).append("/s");
            }
        }
        String p = phrase(phase, window);
        if (!p.isEmpty()) {
            b.append(" · ").append(p);
        }
        return b.toString();
    }

    /** The stable half of the line: what the search is doing, in one or two words. */
    private static String stageWord(sts1solver.Gameplay.Status s, String phase) {
        if ("paused".equals(phase)) {
            return "已暂停";
        }
        if ("stalled".equals(phase)) {
            return "无进展";
        }
        if ("init".equals(phase)) {
            return "准备中";
        }
        if ("done".equals(phase)) {
            return "已就绪";
        }
        if ("idle".equals(phase)) {
            return "空闲";
        }
        if ("backtrack".equals(phase)) {
            return "收尾中";
        }
        if ("deep".equals(phase)) {
            return "深搜中";
        }
        if (s.pct > 0) {
            return "搜索中 " + s.pct + "%";
        }
        return "搜索中";
    }

    /** 1234 -> "1.2k", 1234567 -> "1.2m": the same shortening the panel's node chip uses. */
    private static String nodeText(int nodes) {
        if (nodes < 1000) {
            return String.valueOf(nodes);
        }
        if (nodes < 1000000) {
            return (nodes / 100) / 10.0f + "k";
        }
        return (nodes / 100000) / 10.0f + "m";
    }

    /** The numeric bucket a node count falls in, so the key stops moving on every single node. */
    private static int bucket(int nodes) {
        return nodes < 10000 ? nodes / 100 : nodes / 1000;
    }

    private static long key(sts1solver.Gameplay.Status s, long window, int nodeBucket) {
        long k = window;
        k = k * 31 + phaseCode(phaseName(s));
        k = k * 31 + nodeBucket;
        k = k * 31 + s.layers;
        k = k * 31 + (s.pct < 0 ? 999 : s.pct);
        k = k * 31 + (s.paused ? 1 : 0);
        k = k * 31 + (s.hasPlan ? 1 : 0);
        k = k * 31 + (s.stalledMs >= STALL_MS ? 1 : 0);
        k = k * 31 + s.planSteps;
        return k;
    }

    private static int phaseCode(String phase) {
        if ("paused".equals(phase)) {
            return 1;
        }
        if ("stalled".equals(phase)) {
            return 2;
        }
        if ("init".equals(phase)) {
            return 3;
        }
        if ("deep".equals(phase)) {
            return 4;
        }
        if ("backtrack".equals(phase)) {
            return 5;
        }
        if ("done".equals(phase)) {
            return 6;
        }
        if ("idle".equals(phase)) {
            return 7;
        }
        return 8;
    }

    /** FNV-1a over the phase name, mixed with the window: deterministic, no Random object. */
    private static int hash(String phase, long window) {
        int h = 0x811c9dc5;
        for (int i = 0; i < phase.length(); i++) {
            h ^= phase.charAt(i);
            h *= 0x01000193;
        }
        h ^= (int) (window ^ (window >>> 32));
        h *= 0x01000193;
        return h;
    }
}
