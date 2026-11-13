package sts1solver.search;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.CardLibrary;

/**
 * P2-36 (user m19041 question 1: "can we know other mods' run-level rewards?").
 *
 * <p>Partially yes, and this is the honest part: a card gets flagged when its own class file mentions
 * one of the engine actions that hand out a run-level reward -- {@code IncreaseMaxHpAction},
 * {@code GainGoldAction}, {@code ObtainPotionAction}, {@code UpgradeRandomCardAction},
 * {@code UpgradeSpecificCardAction}, {@code RitualDaggerAction}, {@code LessonLearnedAction}. That
 * covers vanilla cards and every mod card that routes through the vanilla actions.
 *
 * <p>What it cannot do is invent a number: a card flagged here is priced at
 * {@code growth.other} (1 HP per play by default, m19041's "unpriced means 1"), and any card can be
 * priced by hand with a {@code growth.card.<cardId>=<hp>} line in settings.txt. A mod that implements
 * its own action class instead of the vanilla one is NOT flagged -- that is what the hand-priced
 * line is for, and {@link #report()} shows exactly which ids were found so the list can be checked.
 *
 * <p>No initialisation, no behaviour: only the class file's constant pool is searched, once, lazily.
 */
public final class GrowthScan {

    private static final String[] TOKENS = {
            "IncreaseMaxHpAction",
            "GainGoldAction",
            "ObtainPotionAction",
            "UpgradeRandomCardAction",
            "UpgradeSpecificCardAction",
            "RitualDaggerAction",
            "LessonLearnedAction"
    };

    private static Set<String> flagged;
    private static String report = "growth scan: not run";

    private GrowthScan() {
    }

    /** True when this card's class mentions a vanilla action that hands out a run-level reward. */
    public static synchronized boolean isGrowthCard(String cardId) {
        if (cardId == null || cardId.isEmpty()) {
            return false;
        }
        return scan().contains(cardId);
    }

    public static synchronized List<String> flaggedCards() {
        List<String> out = new ArrayList<String>(scan());
        Collections.sort(out);
        return out;
    }

    public static synchronized String report() {
        scan();
        return report;
    }

    public static synchronized void reset() {
        flagged = null;
        report = "growth scan: not run";
    }

    /**
     * -Mode growthscan: write the list where the player can read it (the solver directory), then say
     * DONE so tools/run.ps1 stops waiting.
     */
    public static void run() {
        String rep = report();
        sts1solver.util.Log.info(rep);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(rep).append('\n');
        sb.append("# 这里列出的卡会发局外收益（最大生命 / 金币 / 药水 / 升级 / 永久伤害）。\n");
        sb.append("# 价格：settings.txt 里 growth.<键>=<HP> ，或 growth.card.<cardId>=<HP> 手定价。\n");
        java.util.List<String> ids = flaggedCards();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            sb.append(id).append("  ->  ").append(keyOf(id)).append('\n');
        }
        java.io.PrintWriter out = null;
        try {
            java.io.File file = new java.io.File(sts1solver.SolverMod.dir(), "growth-scan.txt");
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file, false), "UTF-8"));
            out.print(sb.toString());
            out.flush();
            sts1solver.util.Log.info("growth scan: wrote " + file.getAbsolutePath());
        } catch (Throwable t) {
            sts1solver.util.Log.warn("growth scan: could not write the list (" + t + ")");
        } finally {
            if (out != null) {
                out.close();
            }
        }
        sts1solver.util.Log.info("AUTOTEST DONE");
    }

    /** Which settings.txt key prices this card. */
    private static String keyOf(String cardId) {
        if ("Feed".equals(cardId)) {
            return "growth.feed（狂宴：每点最大生命）";
        }
        if ("Genetic Algorithm".equals(cardId)) {
            return "growth.geneticAlgorithm（每次打出）";
        }
        if ("RitualDagger".equals(cardId)) {
            return "growth.ritualDagger（每 +1 永久伤害）";
        }
        if ("LessonLearned".equals(cardId)) {
            return "growth.lessonLearned（每真升级一张牌）";
        }
        if ("Venomology".equals(cardId)) {
            return "growth.alchemize（按一瓶药水的 HP 值）";
        }
        if ("HandOfGreed".equals(cardId)) {
            return "金币由求解器直接计价（1 金 = 80 分，是否计价跟随「局外收益」开关）；growth.handOfGreed 已作废（B146）";
        }
        return "growth.other（未定价来源，每次打出），或 growth.card." + cardId;
    }

    private static Set<String> scan() {
        if (flagged != null) {
            return flagged;
        }
        Set<String> out = new HashSet<String>();
        int cards = 0;
        int noBytes = 0;
        try {
            Map<String, AbstractCard> all = CardLibrary.cards;
            if (all != null) {
                for (Map.Entry<String, AbstractCard> e : all.entrySet()) {
                    AbstractCard card = e.getValue();
                    if (card == null) {
                        continue;
                    }
                    cards++;
                    String pool = constantPool(card.getClass());
                    if (pool == null) {
                        noBytes++;
                        continue;
                    }
                    for (int i = 0; i < TOKENS.length; i++) {
                        if (pool.indexOf(TOKENS[i]) >= 0) {
                            out.add(e.getKey());
                            break;
                        }
                    }
                }
            }
            List<String> ids = new ArrayList<String>(out);
            Collections.sort(ids);
            report = "growth scan: cards=" + cards + " flagged=" + out.size()
                    + " noBytes=" + noBytes + " ids=" + ids;
        } catch (Throwable t) {
            report = "growth scan: failed (" + t + ")";
        }
        flagged = out;
        return flagged;
    }

    private static String constantPool(Class<?> type) {
        InputStream in = null;
        try {
            String path = "/" + type.getName().replace('.', '/') + ".class";
            in = type.getResourceAsStream(path);
            if (in == null && type.getClassLoader() != null) {
                in = type.getClassLoader().getResourceAsStream(path.substring(1));
            }
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "ISO-8859-1");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // closing a jar stream cannot fail in a way that matters here
                }
            }
        }
    }
}
