#!/usr/bin/env python
"""Audit: which presentation effects (AbstractGameEffect subclasses) write combat state in update()?

The sandbox drops effectList/effectsQueue/topLevelEffects at every settle point (World.trimPresentation),
so an effect whose update() changes real state -- a card's attributes, a pile, the player -- must have
that end state applied before it is dropped. mirror/EffectSettle does that for the classes found here.

Usage:
  F:\python\python.exe tools\audit\vfx_state.py <decompiled-src-root> [out.txt]

<decompiled-src-root> is a CFR dump of the game jar that still contains com/megacrit/... (build/decomp6).
The script walks every .java under it, builds the "extends AbstractGameEffect" closure, and prints the
update() lines that look like state writes. It found 4 of 293 classes (3 campfire, 1 combat: ExhaustCardEffect).
"""
import io, os, re, sys

SUSPECT = re.compile(
    r'AbstractDungeon\.actionManager|addToTop\(|addToBot\(|currentHealth|moveToDiscardPile'
    r'|moveToExhaustPile|moveToHand|moveToDrawPile|moveToDeck|exhaustPile|discardPile|drawPile'
    r'|EnergyPanel\.|addPower|resetAttributes|triggerOn|applyPowers|\.limbo|selectedCards'
    r'|\.hand\.|increaseMaxHp|\.heal\(')


def update_bodies(text):
    out = []
    for m in re.finditer(r'void\s+update\s*\(', text):
        i = text.find('{', m.end())
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(text):
            c = text[j]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append((text[:m.start()].count('\n') + 1, text[i:j + 1]))
    return out


def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    root = sys.argv[1]
    texts = {}
    for dp, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.java'):
                p = os.path.join(dp, f)
                texts[p] = open(p, encoding='utf-8', errors='replace').read()
    sup = {}
    for p, t in texts.items():
        m = re.search(r'\bclass\s+(\w+)[^{]*?\bextends\s+([\w\.<>]+)', t)
        if m:
            sup[os.path.basename(p)[:-5]] = m.group(2).split('<')[0].split('.')[-1]
    effects = set()
    changed = True
    while changed:
        changed = False
        for c, s in sup.items():
            if c not in effects and (s == 'AbstractGameEffect' or s in effects):
                effects.add(c)
                changed = True
    by_class = {}
    for p in texts:
        by_class.setdefault(os.path.basename(p)[:-5], p)
    hits = 0
    for c in sorted(effects):
        p = by_class.get(c)
        if not p:
            continue
        found = []
        for ln, body in update_bodies(texts[p]):
            for i, line in enumerate(body.split('\n')):
                s = line.strip()
                if SUSPECT.search(s) and not s.startswith('//'):
                    found.append((ln + i, s))
        if found:
            hits += 1
            print('=== %s   (%s)' % (c, os.path.relpath(p, root).replace(os.sep, '/')))
            for ln, s in found[:10]:
                print('   %d: %s' % (ln, s[:150]))
    print('effect classes with suspicious update(): %d / %d' % (hits, len(effects)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
