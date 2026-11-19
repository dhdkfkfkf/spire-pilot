package sts1solver.ui;

import basemod.BaseMod;
import basemod.ModLabel;
import basemod.ModLabeledToggleButton;
import basemod.ModPanel;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import sts1solver.mirror.CompatMode;
import sts1solver.mirror.HookQuiet;
import sts1solver.util.Log;

/**
 * The mod's config screen: main menu → Mods → Spire Pilot → Config.
 *
 * <p>Since 1.6.8 the screen has two independent switches, each with its cost written next to it in
 * player language: the 15 combat events and the 6 events outside combat. The default is the
 * pre-1.6.7 behaviour (sandbox hooks run, so mod hook effects are part of the plan); a switch
 * isolates that group inside a sandbox window so a buggy listener there cannot freeze the game.
 * The copy says which group freezes usually come from, because the known hang is a combat event.
 */
public final class ModConfig {

    private static ModPanel panel;
    private static ModLabeledToggleButton combatToggle;
    private static ModLabeledToggleButton otherToggle;

    private ModConfig() {
    }

    public static void install() {
        try {
            ModPanel p = new ModPanel();
            float x = 330f;
            label(p, "Spire Pilot — 战斗模拟设置", x, 812f, FontHelper.charTitleFont,
                    Settings.CREAM_COLOR);

            float y = 764f;
            label(p, "计算时，Spire Pilot 会在后台把这场战斗模拟一遍。", x, y);
            y -= 34f;
            label(p, "模拟里也会真的抽牌、受伤、喝药；游戏会照常通知其它 mod，就像你在亲手打。", x, y);
            y -= 34f;
            label(p, "有的 mod 收到通知后会卡住不返回，游戏也跟着卡死：计算一直转，点什么都没反应。", x, y);
            y -= 34f;
            label(p, "下面两个开关决定模拟时哪些通知不再发出去。全不勾 = 和以前完全一样。", x, y);
            y -= 34f;
            label(p, "代价：被跳过的通知里，mod 做的事不算进路线，带 mod 的战斗只当参考。", x, y);
            y -= 34f;
            label(p, "改完立即生效，不用重启游戏。", x, y);

            label(p, "兼容实验模式（两个开关，可分别勾选）", x, 556f,
                    FontHelper.charDescFont, Settings.GOLD_COLOR);

            ModLabeledToggleButton combat = toggle(p,
                    "模拟时不发战斗事件（抽牌、伤害、状态、药水等 "
                            + HookQuiet.COMBAT_METHODS + " 项）",
                    x, 510f, CompatMode.combat(),
                    new java.util.function.Consumer<basemod.ModToggleButton>() {
                        @Override
                        public void accept(basemod.ModToggleButton button) {
                            CompatMode.writeCombat(button.enabled);
                        }
                    });
            ModLabeledToggleButton other = toggle(p,
                    "模拟时不发战斗之外的事件（遗物、药水获取、最大生命、死亡界面等 "
                            + HookQuiet.OTHER_METHODS + " 项）",
                    x, 462f, CompatMode.other(),
                    new java.util.function.Consumer<basemod.ModToggleButton>() {
                        @Override
                        public void accept(basemod.ModToggleButton button) {
                            CompatMode.writeOther(button.enabled);
                        }
                    });

            label(p, "拿不准先勾哪一组，就两组都勾；代价是路线只作参考。", x, 412f,
                    FontHelper.charDescFont, Settings.GOLD_COLOR);
            label(p, "第二个管战斗外的通知：mod 在那儿弹界面、放声音或发奖励。", x, 380f,
                    FontHelper.charDescFont, Settings.GOLD_COLOR);

            BaseMod.registerModBadge(badge(), "Spire Pilot", "dhdkfkfkf",
                    "战斗求解器：计算每回合的出牌路线，可自动执行。", p);
            panel = p;
            combatToggle = combat;
            otherToggle = other;
            Log.info("config: panel registered; " + CompatMode.label());
        } catch (Throwable t) {
            Log.warn("config: cannot register the settings panel: " + t);
        }
    }

    /** Gate seam: S274 checks the Mods screen got its panel. */
    public static ModPanel panel() {
        return panel;
    }

    /** Gate seam: the combat switch reflects the stored setting at startup. */
    public static ModLabeledToggleButton combatToggle() {
        return combatToggle;
    }

    /** Gate seam: the non-combat switch reflects the stored setting at startup. */
    public static ModLabeledToggleButton otherToggle() {
        return otherToggle;
    }

    private static ModLabeledToggleButton toggle(ModPanel p, String text, float x, float y,
                                                 boolean enabled,
                                                 java.util.function.Consumer<basemod.ModToggleButton> callback) {
        ModLabeledToggleButton t = new ModLabeledToggleButton(text, x, y,
                Settings.CREAM_COLOR, FontHelper.charDescFont, enabled, p,
                new java.util.function.Consumer<ModLabel>() {
                    @Override
                    public void accept(ModLabel label) {
                    }
                }, callback);
        p.addUIElement(t);
        return t;
    }

    private static void label(ModPanel p, String text, float x, float y) {
        label(p, text, x, y, FontHelper.charDescFont, Settings.CREAM_COLOR);
    }

    private static void label(ModPanel p, String text, float x, float y, BitmapFont font,
                              Color color) {
        p.addUIElement(new ModLabel(text, x, y, color, font, p,
                new java.util.function.Consumer<ModLabel>() {
                    @Override
                    public void accept(ModLabel label) {
                    }
                }));
    }

    /**
     * A small navy badge. BaseMod draws a badge at its texture's native size (40 px is the size the
     * main-menu row leaves for it), so a 128 px texture overlaps the rows above it.
     */
    private static Texture badge() {
        Pixmap pm = new Pixmap(40, 40, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.13f, 0.18f, 0.25f, 1f));
        pm.fill();
        pm.setColor(new Color(0.36f, 0.55f, 0.72f, 1f));
        pm.fillRectangle(2, 2, 36, 36);
        pm.setColor(new Color(0.13f, 0.18f, 0.25f, 1f));
        pm.fillRectangle(7, 7, 26, 26);
        pm.setColor(new Color(0.72f, 0.86f, 0.98f, 1f));
        pm.fillRectangle(18, 10, 4, 20);
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }
}
