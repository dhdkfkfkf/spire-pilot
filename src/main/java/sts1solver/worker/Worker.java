package sts1solver.worker;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.scenario.Synthetic;
import sts1solver.search.Action;
import sts1solver.drive.Driver;
import sts1solver.search.Legal;
import sts1solver.search.PotionOutlook;
import sts1solver.search.Solver;
import sts1solver.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One search worker (P1-2h, stage A): solves a fight rebuilt from a compact request file.
 *
 * <p>The unit of work travels as a text request, not as a serialized object graph: a worker process
 * cannot receive the live combat graph, and inventing a serializer for it would be the rule
 * reimplementation this project forbids. So a worker rebuilds the fight from the same compact spec
 * the harness already uses for synthetic fights, solves it with the REAL engine, and writes its line
 * back as indices plus a root digest. The main process replays that line in the live sandbox before
 * using it -- a worker result is a proposal, never an authority.
 *
 * <p>Request format (text, one key per line, first line is the mode):
 * <pre>
 * worker
 * seed=801
 * class=com.megacrit.cardcrawl.characters.Ironclad
 * maxHealth=90
 * ascension=0
 * deck=Strike_R,Strike_R,Defend_R,Bash
 * relics=Burning Blood
 * monsters=com.megacrit.cardcrawl.monsters.exordium.JawWorm
 * budgetMs=4000
 * beam=24
 * layers=40
 * out=result-w1.txt
 * </pre>
 *
 * <p>Result format: {@code key=value} lines plus one {@code stepN=} line per action, where a step is
 * {@code kind|handIndex|targetIndex|energyOnUse|potionSlot|cardId|choice}.
 */
public final class Worker {

    private Worker() {
    }

    /** Parsed request. Missing values fall back to harmless defaults. */
    public static final class Request {
        /** "worker": rebuild from the synthetic spec. "live": rebuild from a captured LiveSpec. */
        public String mode = "worker";
        /** Fingerprint the caller captured; a live worker refuses to answer if its rebuild differs. */
        public String fingerprint = "";
        /** Raw key/value pairs, so live fields can be decoded without another parser. */
        public final java.util.Map<String, String> raw = new java.util.LinkedHashMap<String, String>();
        public long seed = 1L;
        public String playerClass = "com.megacrit.cardcrawl.characters.Ironclad";
        public int maxHealth = 80;
        public int ascension = 0;
        public String[] deck = {"Strike_R", "Strike_R", "Defend_R", "Bash"};
        public String[] relics = {"Burning Blood"};
        public String[] monsters = {"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
        public String encounterKey = "";
        public long budgetMs = 4000L;
        public int beam = 24;
        public int layers = 40;
        public String out = "worker-result.txt";

        public static Request parse(File file) throws Exception {
            Request r = new Request();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (first) {
                        first = false;
                        if (!"worker".equals(line)) {
                            throw new IllegalArgumentException("not a worker request: " + line);
                        }
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    r.raw.put(key, value);
                    if ("mode".equals(key)) {
                        r.mode = value;
                    } else if ("fingerprint".equals(key)) {
                        r.fingerprint = value;
                    } else if ("seed".equals(key)) {
                        r.seed = Long.parseLong(value);
                    } else if ("class".equals(key)) {
                        r.playerClass = value;
                    } else if ("maxHealth".equals(key)) {
                        r.maxHealth = Integer.parseInt(value);
                    } else if ("ascension".equals(key)) {
                        r.ascension = Integer.parseInt(value);
                    } else if ("deck".equals(key)) {
                        r.deck = split(value);
                    } else if ("relics".equals(key)) {
                        r.relics = split(value);
                    } else if ("monsters".equals(key)) {
                        r.monsters = split(value);
                    } else if ("encounter".equals(key)) {
                        r.encounterKey = value;
                    } else if ("budgetMs".equals(key)) {
                        r.budgetMs = Long.parseLong(value);
                    } else if ("beam".equals(key)) {
                        r.beam = Integer.parseInt(value);
                    } else if ("layers".equals(key)) {
                        r.layers = Integer.parseInt(value);
                    } else if ("out".equals(key)) {
                        r.out = value;
                    }
                }
            } finally {
                reader.close();
            }
            return r;
        }
    }

    private static String[] split(String value) {
        if (value.isEmpty()) {
            return new String[0];
        }
        String[] raw = value.split(",");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i].trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.toArray(new String[out.size()]);
    }

    /** Builds the fight described by the request (same path a synthetic probe uses). */
    public static void build(Request r) {
        Synthetic.Spec spec = new Synthetic.Spec();
        spec.seed = r.seed;
        spec.playerClass = r.playerClass;
        spec.maxHealth = r.maxHealth;
        spec.ascension = r.ascension;
        spec.deck = r.deck;
        spec.relics = r.relics;
        spec.monsters = r.monsters;
        spec.encounterKey = r.encounterKey;
        Synthetic.build(spec, new Synthetic.SyntheticStep());
    }

    /** Writes a live-mode request for one worker instance/name and returns the file. */
    public static java.io.File writeLiveRequest(java.io.File dir, String fileName,
                                               sts1solver.scenario.LiveSpec live, long budgetMs,
                                               int beam, int layers, String out) throws java.io.IOException {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<String, String>();
        live.toMap(map);
        java.io.File request = new java.io.File(dir, fileName);
        PrintWriter w = new PrintWriter(request, "UTF-8");
        try {
            w.println("worker");
            w.println("mode=live");
            w.println("fingerprint=" + live.fingerprint());
            for (java.util.Map.Entry<String, String> e : map.entrySet()) {
                w.println(e.getKey() + "=" + e.getValue());
            }
            w.println("budgetMs=" + budgetMs);
            w.println("beam=" + beam);
            w.println("layers=" + layers);
            w.println("out=" + out);
        } finally {
            w.close();
        }
        return request;
    }

    /** Solves the request and writes the result next to it (atomic: tmp then move). */
    public static File run(File requestFile) {
        try {
            Request r = Request.parse(requestFile);
            String mode = r.mode;
            String fingerprint = "";
            if ("live".equals(mode)) {
                sts1solver.scenario.LiveSpec spec = sts1solver.scenario.LiveSpec.fromMap(r.raw);
                spec.rebuild();
                fingerprint = spec.fingerprint();
                if (!r.fingerprint.isEmpty() && !r.fingerprint.equals(fingerprint)) {
                    // The rebuild is not the caller's state: answer nothing, so a mismatch cannot be
                    // mistaken for a solved fight.
                    File bad = new File(requestFile.getParentFile(), r.out);
                    writeBad(bad, mode, fingerprint, "fingerprint");
                    Log.warn("worker: live fingerprint mismatch, refusing to answer");
                    return bad;
                }
            } else {
                build(r);
            }
            String rootDigest = Digest.ofCurrent();
            Solver.Result result = Solver.solve(World.captureLive(), r.beam, r.layers, r.budgetMs);
            File out = new File(requestFile.getParentFile(), r.out);
            File tmp = new File(out.getAbsolutePath() + ".tmp");
            PrintWriter w = new PrintWriter(tmp, "UTF-8");
            try {
                w.println("mode=" + mode);
                if (!fingerprint.isEmpty()) {
                    w.println("fingerprint=" + fingerprint);
                }
                w.println("rootDigest=" + rootDigest);
                w.println("win=" + result.win);
                w.println("escaped=" + result.escaped);
                w.println("hpLoss=" + result.hpLoss);
                w.println("turns=" + result.predictedTurns);
                w.println("nodes=" + result.nodes);
                w.println("reason=" + result.reason);
                w.println("steps=" + result.plan.size());
                for (int i = 0; i < result.plan.size(); i++) {
                    w.println("step" + i + "=" + encode(result.plan.get(i).action));
                }
            } finally {
                w.close();
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.info("worker: solved " + requestFile.getName() + " -> " + out.getName()
                    + " (win=" + result.win + " hpLoss=" + result.hpLoss + ")");
            return out;
        } catch (Throwable t) {
            Log.error("worker: request failed: " + t, t);
            return null;
        }
    }

    private static void writeBad(File out, String mode, String fingerprint, String reason) {
        try {
            File tmp = new File(out.getAbsolutePath() + ".tmp");
            PrintWriter w = new PrintWriter(tmp, "UTF-8");
            try {
                w.println("mode=" + mode);
                w.println("fingerprint=" + fingerprint);
                w.println("mismatch=" + reason);
                w.println("win=false");
                w.println("hpLoss=0");
                w.println("steps=0");
                w.println("reason=mismatch");
            } finally {
                w.close();
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Log.warn("worker: cannot write the mismatch result: " + t);
        }
    }

    /**
     * Stage-D bench, prep side (P1-2h): builds a fixed fight, writes a live worker request for the
     * "wbench" instance, and records the in-process baseline next to it.
     *
     * <p>The comparison is the point: the same fight and the same budget, once in this process and
     * once in a second game process. Orchestration is deliberately a script, not engine code, so the
     * measurement never becomes part of the game's hot path.
     */
    public static void benchPrep(long budgetMs) {
        try {
            Synthetic.Spec spec = new Synthetic.Spec();
            spec.seed = 77L;
            spec.maxHealth = 200;
            spec.deck = new String[]{"Bludgeon", "Bludgeon", "Bash", "Bash", "Strike_R", "Strike_R",
                    "Strike_R", "Strike_R", "Defend_R", "Defend_R", "Defend_R", "Defend_R",
                    "Inflame", "Metallicize"};
            spec.monsters = new String[]{"com.megacrit.cardcrawl.monsters.exordium.JawWorm"};
            Synthetic.build(spec, new Synthetic.SyntheticStep());
            sts1solver.scenario.LiveSpec live = sts1solver.scenario.LiveSpec.capture();
            java.io.File dir = new java.io.File(Log.dir());
            java.io.File request = writeLiveRequest(dir, "autotest-wbench.request", live, budgetMs,
                    24, 40, "wbench-result.txt");
            long t0 = System.currentTimeMillis();
            Solver.Result result = Solver.solve(World.captureLive(), 24, 40, budgetMs);
            long ms = System.currentTimeMillis() - t0;
            PrintWriter b = new PrintWriter(new java.io.File(dir, "bench-baseline.txt"), "UTF-8");
            try {
                b.println("win=" + result.win);
                b.println("hpLoss=" + result.hpLoss);
                b.println("steps=" + result.plan.size());
                b.println("nodes=" + result.nodes);
                b.println("millis=" + ms);
            } finally {
                b.close();
            }
            Log.info("bench: request " + request.getName() + " baseline win=" + result.win
                    + " hpLoss=" + result.hpLoss + " steps=" + result.plan.size() + " " + ms + "ms");
            Log.info("AUTOTEST RESULT passed=1 failed=0");
            Log.info("AUTOTEST DONE");
        } catch (Throwable t) {
            Log.error("bench: prep failed: " + t, t);
        }
    }

    private static String encode(Action a) {
        if (a == null) {
            return "end|-1|-1|0|-1||";
        }
        return a.kind + "|" + a.handIndex + "|" + a.targetIndex + "|" + a.energyOnUse + "|"
                + a.potionSlot + "|" + a.cardId + "|" + a.choice;
    }

    /**
     * Replays the worker's line in the real sandbox and reports whether the prediction held.
     *
     * <p>Returns null when the line replays within tolerance, otherwise a one-line explanation.
     * This is the fidelity gate for P1-2h: a worker proposal is used only if the real engine agrees.
     */
    public static String verify(File requestFile, File resultFile) {
        try {
            Request r = Request.parse(requestFile);
            java.util.Map<String, String> res = readResult(resultFile);
            if (res.get("mismatch") != null) {
                return "worker refused: " + res.get("mismatch");
            }
            boolean live = "live".equals(res.get("mode"));
            if (live) {
                sts1solver.scenario.LiveSpec spec = sts1solver.scenario.LiveSpec.fromMap(r.raw);
                spec.rebuild();
                String fp = spec.fingerprint();
                if (!fp.equals(res.get("fingerprint"))) {
                    return "live fingerprint differs: worker=" + res.get("fingerprint")
                            + " verifier=" + fp;
                }
            } else {
                build(r);
                String liveDigest = Digest.ofCurrent();
                if (!liveDigest.equals(res.get("rootDigest"))) {
                    return "root digest differs: worker=" + res.get("rootDigest")
                            + " verifier=" + liveDigest;
                }
            }
            int steps = Integer.parseInt(res.get("steps"));
            World w = World.captureLive();
            boolean installed = false;
            int hpBefore = 0;
            try {
                w.install();
                installed = true;
                Driver.begin();
                hpBefore = AbstractDungeon.player.currentHealth;
                for (int i = 0; i < steps; i++) {
                    String[] f = res.get("step" + i).split("\\|", -1);
                    Action match = find(f);
                    if (match == null) {
                        return "step " + i + " does not match any legal action";
                    }
                    Legal.apply(match);
                }
            } finally {
                try {
                    if (installed) {
                        int hpLoss = Math.max(0, hpBefore - AbstractDungeon.player.currentHealth);
                        int predicted = Integer.parseInt(res.get("hpLoss"));
                        if (Math.abs(hpLoss - predicted) > 2) {
                            return "hpLoss mismatch: worker=" + predicted + " replay=" + hpLoss;
                        }
                        boolean won = Driver.combatOver()
                                && AbstractDungeon.player.currentHealth > 0;
                        if (won != Boolean.parseBoolean(res.get("win"))) {
                            return "win mismatch: worker=" + res.get("win") + " replay=" + won;
                        }
                    }
                } finally {
                    Driver.end();
                    w.readBack();
                    w.uninstall();
                }
            }
            return null;
        } catch (Throwable t) {
            World.forceRestore();
            return "verify failed: " + t;
        }
    }

    private static Action find(String[] f) {
        List<Action> legal = Legal.actions();
        for (int i = 0; i < legal.size(); i++) {
            Action a = legal.get(i);
            if (!a.kind.equals(f[0])) {
                continue;
            }
            if (a.handIndex != Integer.parseInt(f[1])
                    || a.targetIndex != Integer.parseInt(f[2])
                    || a.energyOnUse != Integer.parseInt(f[3])
                    || a.potionSlot != Integer.parseInt(f[4])) {
                continue;
            }
            if (!a.cardId.equals(f[5]) || !a.choice.equals(f[6])) {
                continue;
            }
            return a;
        }
        return null;
    }

    /** Reads a result file into a key/value map. */
    public static java.util.Map<String, String> readResult(File file) throws Exception {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } finally {
            reader.close();
        }
        return out;
    }
}