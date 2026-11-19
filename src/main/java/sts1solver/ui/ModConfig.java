package sts1solver.ui;

import basemod.BaseMod;
import basemod.ModLabel;
import basemod.ModLabeledToggleButton;
import basemod.ModPanel;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import sts1solver.mirror.CompatMode;
import sts1solver.util.Log;

/**
 * The mod's config screen: main menu → Mods → Spire Pilot → Config.
 *
 * <p>One toggle, and the two modes' costs written next to it in player language. The default is the
 * pre-1.6.7 behaviour (sandbox hooks run, so mod hook effects are part of the plan); the option
 * isolates the sandbox from third-party listeners so a buggy hook cannot freeze the game.
 */
public final class ModConfig {

    private static ModPanel panel;
    private static ModLabeledToggleButton toggle;

    private ModConfig() {
    }

    public static void install() {
        try {
            ModPanel p = new ModPanel();
            float x = 350f;
            float y = 780f;
            label(p, "Spire Pilot — 战斗模拟设置", x, y);
            y -= 46f;
            label(p, "默认模式（兼容实验模式 关）", x, y);
            y -= 30f;
            label(p, "好处：mod 的战斗钩子照常运行，钩子里的效果也算进路线。", x, y);
            y -= 30f;
            label(p, "风险：钩子若在模拟中卡住不返回，整个游戏会卡死。", x, y);
            y -= 50f;
            label(p, "兼容实验模式（开）", x, y);
            y -= 30f;
            label(p, "好处：模拟不通知其它 mod，第三方钩子卡不死游戏。", x, y);
            y -= 30f;
            label(p, "代价：钩子里的效果不算进路线，含 mod 的战斗只看作参考。", x, y);
            y -= 44f;
            ModLabeledToggleButton t = new ModLabeledToggleButton(
                    "启用兼容实验模式（模拟时跳过第三方战斗钩子）", x, y,
                    Settings.CREAM_COLOR, FontHelper.charDescFont, CompatMode.isolation(), p,
                    new java.util.function.Consumer<ModLabel>() {
                        @Override
                        public void accept(ModLabel label) {
                        }
                    },
                    new java.util.function.Consumer<basemod.ModToggleButton>() {
                        @Override
                        public void accept(basemod.ModToggleButton button) {
                            CompatMode.write(button.enabled);
                        }
                    });
            p.addUIElement(t);
            y -= 34f;
            label(p, "改完立即生效，并写入 settings.txt（compatHooks=on/off）。", x, y);
            BaseMod.registerModBadge(badge(), "Spire Pilot", "dhdkfkfkf",
                    "战斗求解器：计算每回合的出牌路线，可自动执行。", p);
            panel = p;
            toggle = t;
            Log.info("config: panel registered; " + CompatMode.label());
        } catch (Throwable t) {
            Log.warn("config: cannot register the settings panel: " + t);
        }
    }

    /** Gate seam: S274 checks the Mods screen got its panel. */
    public static ModPanel panel() {
        return panel;
    }

    /** Gate seam: the toggle reflects the stored mode at startup. */
    public static ModLabeledToggleButton toggle() {
        return toggle;
    }

    private static void label(ModPanel p, String text, float x, float y) {
        p.addUIElement(new ModLabel(text, x, y, Settings.CREAM_COLOR,
                FontHelper.charDescFont, p, new java.util.function.Consumer<ModLabel>() {
                    @Override
                    public void accept(ModLabel label) {
                    }
                }));
    }

    /** A small navy badge: the mod list only grows a Config button for a registered badge. */
    private static Texture badge() {
        Pixmap pm = new Pixmap(128, 128, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.13f, 0.18f, 0.25f, 1f));
        pm.fill();
        pm.setColor(new Color(0.36f, 0.55f, 0.72f, 1f));
        pm.fillRectangle(8, 8, 112, 112);
        pm.setColor(new Color(0.13f, 0.18f, 0.25f, 1f));
        pm.fillRectangle(20, 20, 88, 88);
        pm.setColor(new Color(0.72f, 0.86f, 0.98f, 1f));
        pm.fillRectangle(56, 36, 16, 56);
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }
}
