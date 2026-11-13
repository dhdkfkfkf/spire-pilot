# Design notes

These are the decisions that were not obvious, why they exist, and what they cost. Everything here was
paid for by a bug in this workspace's earlier attempt (`sts1-combat-solver`), which is referenced as
"the predecessor".

## 1. Mirror, not model

The predecessor wrote a model of the game: `MCard`, `MBattle`, card effect tables per character,
monster move tables, relic tables, its own RNG streams. Roughly two years of table maintenance later,
its own notes catalogue the drift: a missing ascension tier on `JawWorm.Chomp` turned a 12-damage
intent into a 7-damage + 5-block move on the panel; ghost moves made live intents unmatchable; the
shuffle point disagreed with the engine.

Those notes are the strongest argument for this design: **the failure mode was never the search, it
was the transcription**. So this build has no card table, no monster table, no relic table. A card is
the game's object; a step is the game's own `use()`.

## 2. Clone: constructor-free, identity-preserving

`Cloner` allocates with `sun.misc.Unsafe.allocateInstance` and copies every non-static field, so a
card is not re-initialised (no texture reload, no RNG re-roll, no registry registration) -- it is the
same value graph.

Identity matters: a `CardGroup` holds cards that also appear in an action's captured state; a
`MonsterGroup` holds monsters referenced by the room, by intents and by queued actions. One
`IdentityHashMap` per copy keeps those aliases as aliases, which also terminates cycles.

Two bugs in this file are worth remembering because both looked "almost right":

* **Boxed primitives.** `Field.get` returns a **new box** every call, so `if (copy != original)` was
  true for every int/boolean and the write was skipped. The copy had a player at 0/0 HP and empty
  piles while its digest looked plausible.
* **Array elements.** The same guard on object arrays left `null` holes for shared elements
  (`ArrayList<Byte> moveHistory` became `[null]` and the monster AI threw).

**Rule: after a copy, write unconditionally.** A skipped write is invisible; a redundant write is not.

## 3. What is copied, linked, or shared

`ClonePolicy` decides by role:

* **Copied** -- anything combat logic reads or writes: creatures, cards, powers, relics, potions,
  orbs, stances, action queues, RNGs, and mutable value holders such as `Color` (cards mutate tint
  and transparency in place).
* **Linked** -- `MapRoomNode`'s map neighbours and its VFX list. The node itself must be COPIED (it
  contains the room); its graph must not be (copying it means copying the map per search node).
  Getting this wrong once made the sandbox's monster and the live monster the *same object*, so a
  simulated play damaged the live fight. The isolation test caught it.
* **Shared** -- GL textures, fonts, sprite batches, audio, spine skeletons, loggers, localization
  tables, VFX objects, most UI screens. Duplicating a `Texture` would create a second GL handle for
  the same image; mutating the copy would desynchronise the atlas, and combat never branches on it.

## 4. World = statics + graph, swapped in and out

Combat state is not a tree hanging off `AbstractDungeon.player`. It is also

* 20 object-graph roots (player, action manager, room node, two selection screens, overlay menu,
  13 RNG streams, one `MetricData`),
* 6 containers whose **contents** are state (the four VFX lists, tip counters, the draw queue),
* 4 mutable `Color` holders (written back in place, because other objects alias the same instance),
* 423 generated scalar fields -- turn counters, `EnergyPanel.totalCount` (the field card costs are
  gated on), relic per-combat flags, score counters, screen latches.

Container fields are installed as **new** containers so the live list object never receives a write.
`World.fork()` is the search's branch primitive; `reconstruct` replays a plan to record predictions.

## 5. Driving a frame-timed engine

Three obstacles, three mechanisms:

* **Actions are frame-timed** (`AbstractGameAction.tickDuration` subtracts `Gdx.graphics.getDeltaTime`).
  `Graphics` is an interface, so a dynamic proxy reports a fixed 2.0 s delta and each action finishes
  in a couple of updates.
* **Simulation must be silent**: `Settings.SOUND_VOLUME` is zeroed for the duration (and volume fields
  are deliberately excluded from the mirror, or installing a world would un-mute it).
* **Draws wait on presentation state**: `DrawCardAction` returns *without drawing* while
  `SoulGroup.isActive()`. Souls become ready in `SoulGroup.update()`, which the game calls once per
  frame from `AbstractRoom.update()`. Driving the engine from the main menu means no frames, so the
  opening draw stalled forever until `Driver` began pumping `room.souls.update()`. This is now part of
  the driver, not of the sandbox: any caller driving the engine needs it.

Also: the End Turn button does **two** things (`actionManager.endTurn()` latches `turnHasEnded`, the
room's `endTurn()` queues the hand discard and end-of-turn triggers). Doing one and not the other
either never advances the turn or never discards the hand. Both are in `Driver.endTurn()`.

## 6. Search

Beam search over worlds. Scoring is one integer, ordered so that survival dominates:

```
dead        -> -50_000_000 - hpLoss*100
win         -> +10_000_000 - hpLoss*2000 - potionsUsed*20_000 - layer
ongoing     ->                - hpLoss*2000 - potionsUsed*20_000 - enemyHp*20 + block*2 - layer
```

Enemy HP has a positive weight **and that is load-bearing**: without it the root node (nothing lost,
nothing gained) outscores every real play, and the search returns an empty plan while reporting a
0-HP-loss "victory". That bug shipped once in this file.

Dedupe by state digest, beam 24-32 wide, one action per layer, wall-clock budget. Legality comes from
`AbstractCard.canUse` -- the game's own gate, including energy, target legality and card restrictions.

## 7. Execution audit

A plan is a list of `Action`s carrying **indices**, never references: a hand index and a monster-group
index is what a click means, and it survives cloning, replay and execution. Each step also carries the
digest the replay predicted.

`Gameplay.executeStep()` applies one step and compares the live digest with the prediction. A mismatch
means the engine disagreed with the mirror (or the player acted) -- the plan is dropped and the status
line says which step diverged. This is the runtime counterpart of the differential test.

## 8. What is tested, and how it can fail

| Test | Catches |
| --- | --- |
| Installed mirror identifies with live | missed roots/statics, aliasing, incomplete clone |
| Same action live vs mirror | completeness: any missing field diverges here |
| 12 steps across a turn boundary | turn start/end machinery, RNG, shuffles, powers, draw |
| Plan replay vs live execution | the search's own predictions |
| Live digest before/after a search/sandbox session | leaks into the live fight |
| Generated static inventory (457 fields) | statics the mirror forgot |
| Fight played twice (solver vs naive policy) | the search being decorative |

The leak inventory is **generated from the jar**, not curated, precisely because the predecessor's
hand-curated list omitted the one field that mattered (`EnergyPanel.totalCount`). An empty inventory
is reported as a **failure**, because "nothing changed" about nothing is the false green this harness
exists to refuse.

## 9. Deliberate non-goals for this build

* Modelling card-choice prompts: detected and refused rather than guessed. The next step is to branch
  on the prompt (the engine's selection screens are already mirrored and are the natural fork point).
* Pre-combat / map planning: the reference project achieves it with a headless game worker; STS1's
  asset/static initialisation chain blocks that, and the predecessor spent a milestone discovering it.
* Parallel search: the world swap mutates JVM-global statics by construction.
* Modelling third-party modded content: it is the game's code, and it runs -- unless it opens a
  prompt, which is refused like vanilla prompts are.

## 10. Two live-only bugs, and the gates that now exist for them

Both were invisible to every test that existed before the first real fight, and both are now gated.

### "The panel never appeared"

The overlay drew at `Gdx.graphics.getHeight()` -- window pixels -- while the sprite batch is
projected onto the game's virtual resolution. In fullscreen 2560x1600 the panel was placed above the
top of the logical screen: the solve logged correctly and nothing was visible. Fixed by using
`Settings.WIDTH/HEIGHT`, and two things were added because "nothing on screen" had been
indistinguishable from "no plan":

* a hint strip the moment a combat starts, so the overlay is visible before any solve;
* **F12**, which draws a fixed demo banner regardless of solver state -- the one-key test of "can this
  panel draw at all";
* HUD draw failures are now LOGGED with the exception (they used to be swallowed).

### "Cards turn black after F8"

`ClonePolicy.denyRecurse()` refuses to walk into `com.badlogic.gdx.graphics.*` so the cloner never
descends into textures. `Color` lives in that package and is a **mutable value holder**, not a
resource -- but it was caught by the same rule, so every `Color` inside a copied card was **shared**
with the live card. One simulated card play that tints itself repainted the live hand.

The proof came from a new gate, not from reading code: the solve is wrapped in a render-state
snapshot (every static `Color` in the game, plus each card's `Color` fields and
`transparency/drawScale/angle`). Before the fix it reported:

```
RENDER STATE CHANGED BY SEARCH: card hand[0]#1d1a8843 Strike_R.tintColor: ...,0.0 -> ...,1.0
... 10 field(s) changed, 10 repaired
```

`tintColor` at alpha 1 is a dark tint over the card face: that is the black card. After the fix the
same gate reports `repaired=0`, and there is a direct unit check that a `Color` inside a copied graph
is a different object and that mutating the copy leaves the original alone.

Three layers now exist, in the order they should fail:

1. **Prevention** -- `Color` is copied; every static `Color` in the game (130 fields, discovered by
   scanning the jar) is mirrored with in-place save/restore.
2. **Detection + repair** -- every solve compares the render snapshot before and after and repairs
   any difference, logging the field names.
3. **The suite gate** -- `search leaves card render state unchanged` fails if any of it moves.

The lesson generalises past colours: **a combat digest only compares the numbers combat logic reads.**
Anything the RENDERER reads needs its own gate, or a broken picture passes every test.
