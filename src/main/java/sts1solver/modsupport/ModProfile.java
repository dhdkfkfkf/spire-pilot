package sts1solver.modsupport;

import sts1solver.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * A human-readable name for a mod's class prefixes.
 *
 * <p><b>Not a permission list any more</b> (user m11229): every mod is accepted and simulated by
 * running its own code in the sandbox, so nothing here decides whether an answer may be trusted.
 * A profile is optional metadata -- it turns {@code CAI_NONG.card.Foo} into "CaiNong" in the log
 * and the panel. The
 * {@code trust} column is parsed and kept for old files, but no code path gates on it.
 *
 * <p>Nothing here executes mod code -- a profile is data:
 *
 * <pre>
 *   # id | display name | package prefixes it owns (comma separated)
 *   cainong | CaiNong | CAI_NONG.,CaiNongMod
 * </pre>
 *
 * <p>Why a file instead of code: integrating a mod is mostly a question of *classification* (which
 * classes are that mod's, which of its statics must be mirrored, which of its cards open a choice
 * screen). Keeping it data means a new mod does not require a rebuild, and a wrong profile is visible
 * in the file rather than buried in a branch.
 *
 * <p>Lookup order: {@code <game>/mods/spire-pilot-content/profiles.txt}, then
 * {@code <user.home>/spire-pilot/content/profiles.txt}. A missing file simply means "no mod is
 * integrated", which is the vanilla-only default.
 */
public final class ModProfile {

    public final String id;
    public final String name;
    /** Class-name prefixes this mod owns; a class matching any of them belongs to the mod. */
    public final String[] prefixes;
    /**
     * Legacy column, still parsed so existing files keep loading. It no longer gates anything: a mod
     * is accepted by default (m11229). Kept only so an old line with a {@code trust} column does not
     * change how that mod is displayed.
     */
    public final boolean trusted;

    public ModProfile(String id, String name, String[] prefixes) {
        this(id, name, prefixes, false);
    }

    public ModProfile(String id, String name, String[] prefixes, boolean trusted) {
        this.id = id;
        this.name = name;
        this.prefixes = prefixes;
        this.trusted = trusted;
    }

    public boolean owns(String className) {
        for (int i = 0; i < prefixes.length; i++) {
            String p = prefixes[i];
            if (p != null && !p.isEmpty() && className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static List<ModProfile> cache;

    public static synchronized List<ModProfile> all() {
        if (cache != null) {
            return cache;
        }
        List<ModProfile> out = new ArrayList<ModProfile>();
        for (String path : candidatePaths()) {
            File f = new File(path);
            if (!f.isFile()) {
                continue;
            }
            try {
                out.addAll(load(f));
                Log.info("mod profiles: loaded " + out.size() + " from " + f);
            } catch (Throwable t) {
                Log.warn("mod profiles: cannot read " + f + ": " + t);
            }
        }
        cache = out;
        return out;
    }

    /** Test seam: parse a profile file's text. */
    public static List<ModProfile> parse(String text) {
        List<ModProfile> out = new ArrayList<ModProfile>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length < 3) {
                Log.warn("mod profiles: bad line " + (i + 1) + ": " + line);
                continue;
            }
            String[] prefixes = parts[2].split(",");
            for (int k = 0; k < prefixes.length; k++) {
                prefixes[k] = prefixes[k].trim();
            }
            boolean trusted = false;
            if (parts.length >= 4) {
                String flag = parts[3].trim();
                trusted = "trust".equalsIgnoreCase(flag) || "trusted".equalsIgnoreCase(flag)
                        || "yes".equalsIgnoreCase(flag);
            }
            out.add(new ModProfile(parts[0].trim(), parts[1].trim(), prefixes, trusted));
        }
        return out;
    }

    /** Display names of the profiles that own at least one of these classes. */
    public static List<String> covering(List<String> classNames) {
        List<String> out = new ArrayList<String>();
        List<ModProfile> profiles = all();
        for (int p = 0; p < profiles.size(); p++) {
            ModProfile profile = profiles.get(p);
            for (int i = 0; i < classNames.size(); i++) {
                if (profile.owns(classNames.get(i))) {
                    out.add(profile.name);
                    break;
                }
            }
        }
        return out;
    }

    /** Display names of the TRUSTED profiles that own at least one of these classes. */
    public static List<String> trustedCovering(List<String> classNames) {
        List<String> out = new ArrayList<String>();
        List<ModProfile> profiles = all();
        for (int p = 0; p < profiles.size(); p++) {
            ModProfile profile = profiles.get(p);
            if (!profile.trusted) {
                continue;
            }
            for (int i = 0; i < classNames.size(); i++) {
                if (profile.owns(classNames.get(i))) {
                    out.add(profile.name);
                    break;
                }
            }
        }
        return out;
    }

    /** True when every class in the list is owned by a TRUSTED profile. */
    public static boolean allTrusted(List<String> classNames) {
        List<ModProfile> profiles = all();
        for (int i = 0; i < classNames.size(); i++) {
            boolean trusted = false;
            for (int p = 0; p < profiles.size(); p++) {
                ModProfile profile = profiles.get(p);
                if (profile.trusted && profile.owns(classNames.get(i))) {
                    trusted = true;
                    break;
                }
            }
            if (!trusted) {
                return false;
            }
        }
        return true;
    }

    /** True when every non-vanilla class found belongs to some integrated profile. */
    public static boolean coversAll(List<String> classNames) {
        List<ModProfile> profiles = all();
        for (int i = 0; i < classNames.size(); i++) {
            String className = classNames.get(i);
            boolean owned = false;
            for (int p = 0; p < profiles.size(); p++) {
                if (profiles.get(p).owns(className)) {
                    owned = true;
                    break;
                }
            }
            if (!owned) {
                return false;
            }
        }
        return true;
    }

    private static List<String> candidatePaths() {
        List<String> out = new ArrayList<String>();
        String gameMods = System.getProperty("user.dir", ".") + File.separator + "mods"
                + File.separator + "spire-pilot-content" + File.separator + "profiles.txt";
        out.add(gameMods);
        String home = System.getProperty("user.home", ".");
        out.add(home + File.separator + "spire-pilot" + File.separator + "content"
                + File.separator + "profiles.txt");
        return out;
    }

    private static List<ModProfile> load(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return parse(sb.toString());
    }
}