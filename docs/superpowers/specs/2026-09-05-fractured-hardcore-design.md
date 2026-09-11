# Fractured Hardcore (`hcheart`) — Design Spec

Date: 2026-09-05 · Target: Minecraft Java **26.2**, Fabric Loader 0.19.5, Fabric API 0.159.0+26.2

This document is the implementation baseline for a server-side Fabric mod that replaces vanilla
hardcore's instant permadeath with a three-tier survival system (Downed → True death → Final life)
plus a craftable Crimson Heart that partially undoes death penalties. It follows the owner's brief
closely; every deliberate deviation is listed in §12 with the reason.

Guiding rule from the brief: **reliability outranks features; boring beats clever.**

---

## 1. Toolchain facts verified against the real 26.2 artifacts

| Item | Value | Why it matters |
|---|---|---|
| Mappings | **Mojang official** (`loom.officialMojangMappings()`, Loom default) | Yarn publishes **no** mappings for 26.2. Every symbol in the brief is translated (see §13). |
| Java | **25** (`javaVersion.majorVersion = 25` in the 26.2 manifest) | JDK 25 installed via Homebrew for the build; `release = 25`. |
| Loom | 1.17 (resolved 1.17.20) | From the official 26.2 example mod. |
| Gradle | 9.5.1 | From the official 26.2 example mod wrapper. |
| Fabric API | 0.159.0+26.2 | Latest for 26.2 at time of writing. |
| Mixin compat | `JAVA_25`, MixinExtras bundled with Loader | `@WrapOperation` available. |

Key vanilla facts (read from the decompiled 26.2 sources):

- `ServerLivingEntityEvents.ALLOW_DEATH` is a redirect of the **second `isDeadOrDying()`** in
  `LivingEntity.hurtServer`, i.e. *before* `checkTotemDeathProtection` and `die()`. Cancelling
  leaves health ≤ 0; the handler must set health itself. Returning `true` still lets a held
  Totem of Undying fire, so **a death is not committed until `die()` runs**.
- `ServerLivingEntityEvents.AFTER_DEATH` for players fires at `ServerPlayer.die` TAIL (once).
- `ServerPlayerEvents.AFTER_RESPAWN` fires at `PlayerList.respawn` TAIL, after the new
  `ServerPlayer` is in the player list with health set.
- `ServerPlayerEvents.JOIN` fires at `PlayerList.placeNewPlayer` RETURN.
- Hardcore → spectator switch lives in `ServerGamePacketListenerImpl.handleClientCommand`
  (`if (this.server.isHardcore()) player.setGameMode(SPECTATOR)`).
- The client decides "Respawn" vs "Spectate world" (and the hardcore heart texture) from the
  `hardcore` boolean in `ClientboundLoginPacket`, built in `placeNewPlayer` from
  `levelData.isHardcore()`. **This flag is sent only at login; no later packet updates it.**
- Mob targeting funnels through `LivingEntity.canAttack(target)` → `target.canBeSeenAsEnemy()`
  (`Player` overrides `canBeSeenAsEnemy`). `Mob.setTarget` filters through `canAttack` too.
- `Warden.canAttack` → `Warden.canTargetEntity`, and its `AngerManagement` is ticked with
  `canTargetEntity` as the validity predicate. One mixin there covers sniffing, vibrations, sonic boom.
- `Player.updatePlayerPose()` is called every tick from `Player.tick()` on both sides.
- `SavedDataType(Identifier, Supplier, Codec, DataFixTypes)` requires a **non-null** DataFixTypes;
  `SavedDataStorage.readTagFromDisk` calls `type.update(...)` unconditionally.
  `DataFixTypes.SAVED_DATA_COMMAND_STORAGE` is registered as `DSL::remainder` (opaque) in every
  schema — it is the type vanilla uses for arbitrary user NBT (`/data storage`).
- `MinecraftServer.getDataStorage()` is the server-level (overworld `data/` folder) saved-data store.
- `SavedDataStorage.saveAndJoin()` synchronously writes all dirty saved data.
- `ServerPlayer.restoreFrom` copies **attribute base values** from the old player on respawn.
- `GameTestHelper.makeMockServerPlayerInLevel()` is `@Deprecated(forRemoval)`; the gametest
  module ships its own equivalent helper.

---

## 2. Non-negotiable constraints (unchanged from the brief)

1. **Server-side only.** Vanilla packets only: boss bar, action bar, chat, pose, glow flag,
   particles, sounds. No custom item registration: the Crimson Heart is a Nether Star with
   `minecraft:custom_data {hcheart: true}`.
2. **This mod owns the death path.** No coexistence with other death-hooking mods.
3. **Correctness never depends on a player being online.** State lives in persistent storage;
   live attributes are derived from it and reapplied on join and on respawn.
4. **Idempotence.** Health is never adjusted incrementally; always recomputed from stored state.
5. **Write state before consuming resources.** Granting a free item on crash is acceptable;
   deleting one is not.

---

## 3. State model

Per player, persisted (world-level `SavedData`, keyed by UUID):

```java
record PlayerRecord(int deaths, int restoresUsed, long downedUntilMs, long downedPausedMs, boolean pendingKill, String lastKnownName)
```

Derived (pure Java, unit-tested, no Minecraft imports):

```java
int     maxHearts()    { return Math.max(4, 10 - 2 * deaths); }   // 10 → 8 → 6 → 4
boolean finalLife()    { return deaths >= 3; }                    // alive at 4 hearts, no safety net
boolean eliminated()   { return deaths >= 4; }                    // run over → spectator
int     restoreCost()  { return restoresUsed + 1; }               // 1, 2, 3, … never resets
boolean isDowned()     { return downedUntilMs > 0; }                        // 0.1.4: wall-clock deadline (epoch ms)
boolean isDownedPaused() { return isDowned() && downedPausedMs > 0; }     // 0.1.3: a revive channel holds the clock
boolean hasLegacyClock() { return legacyDeadlineTicks > 0; }               // 0.1.4: ≤ 0.1.3 tick deadline, converted on the first tick
// pendingKill (0.1.4): bled out offline; death already counted, the vanilla kill is owed on the next join
```

**`eliminated()` is an addition.** The brief uses `finalLife()` both for "the downed state no
longer triggers" (deaths ≥ 3) and for "no respawn button / spectator" — but a player whose third
death just happened must still respawn (into their final life at 4 hearts). The respawn/spectator
decision therefore keys off `eliminated()` (deaths ≥ 4). See §12-D2.

Invariants:

- `deaths` increments only in the true-death handler; decrements only by Heart restore, never below 0.
- `restoresUsed` never decreases (except `/hc reset` / `/hc set`).
- `downedUntilMs` is a wall-clock deadline (epoch milliseconds), 0 when not downed. World time was
  used until 0.1.3; it stops while the server is empty (vanilla pause-when-empty) or down (D24).
- `pendingKill` (0.1.4) means the clock ran out while the player was offline: the death is already
  counted; the vanilla kill is applied once they join and their client has loaded, and
  `AFTER_DEATH` settles it without counting. Construction zeroes any clock when it is set (D22).
- `downedPausedMs` (0.1.3) is what is left on the clock while a revive channel holds it; 0 means
  the clock is running. Only `pauseDowned`/`resumeDowned` change it; a fresh clock or a clear resets
  it, and a value without a clock is dropped on construction. `downedExpired` is false while paused.
- `lastKnownName` is refreshed on every join; it lets `/hc info`, the scoreboard and the audit
  log work for offline players.

All mutation goes through **one** service class (`HeartStateService`). Every mutation:
1. replaces the record in the map, 2. marks dirty, 3. **synchronously flushes** the saved data to
disk (`saveAndJoin()`), 4. appends an audit-log line, 5. syncs the scoreboard score. Mutations are
rare (deaths, restores, downed entry/exit, admin commands), so the synchronous flush is cheap and
closes the crash window in the safe direction.

Storage: `SavedDataType<HeartState>` with id `hcheart:hcheart`, file `<world>/data/hcheart.dat`,
codec `unboundedMap(UUIDUtil.STRING_CODEC, PlayerRecord codec)`, DataFixTypes
`SAVED_DATA_COMMAND_STORAGE` (opaque, never rewritten by vanilla fixers). Every field is
`optionalFieldOf` with a default so a partially damaged file still parses. A parse failure is
logged at ERROR by vanilla; the append-only audit log (`logs/hcheart-audit.log`) is the recovery
record.

---

## 4. Health service

```java
static final Identifier PENALTY_ID = Identifier.fromNamespaceAndPath("hcheart", "heart_penalty");

void normalize(ServerPlayer p, PlayerRecord rec) {
    AttributeInstance attr = p.getAttribute(Attributes.MAX_HEALTH);
    attr.setBaseValue(20.0);                       // own the base layer, unconditionally
    attr.removeModifier(PENALTY_ID);
    double delta = rec.maxHearts() * 2.0 - 20.0;
    if (delta != 0) attr.addTransientModifier(new AttributeModifier(PENALTY_ID, delta, ADD_VALUE));
    if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
}
```

- Called from **join**, **AFTER_RESPAWN**, **Heart restore**, and `/hc set|reset`.
- **Transient**, not permanent, modifier (deviation §12-D5): nothing of ours is written into
  `player.dat`; the join/respawn handlers are the single source of truth, and removing the mod
  leaves no residue. Vanilla resets attributes on respawn anyway (only base values are copied,
  which `setBaseValue(20)` overrides).
- Absorption and Health Boost are **allowed** to stack (decision §11).

---

## 5. Tier 1 — Downed

**Trigger** (`ServerLivingEntityEvents.ALLOW_DEATH`, entity is `ServerPlayer`):

| Condition | Result |
|---|---|
| damage source `is(BYPASSES_INVULNERABILITY)` (void, `/kill`, bleed-out kill) | allow death (true) |
| already downed | allow death (only bypass damage can reach here) |
| `finalLife()` | allow death (vanilla totem check then runs — totem = final-life insurance) |
| otherwise | **enter downed**, return false, `setHealth(1)` |

**Entry** (`DownedManager.enter`):
1. `downedUntilMs = wall-clock now + 180 000` → persisted + flushed **first**.
2. `setHealth(1)`; clear fire (`clearFire()`); stop using item.
3. Glow: `setGlowingTag(true)` (entity flag, not the status effect).
4. Movement: transient attribute modifiers `hcheart:downed_speed` (MOVEMENT_SPEED,
   −75 % `ADD_MULTIPLIED_TOTAL`; −50 % before 0.1.2, see D18) and `hcheart:downed_jump` (JUMP_STRENGTH, −100 %). Both are
   client-synced attributes, so the vanilla client honours them without a mod.
5. Pose: `setPose(SWIMMING)` now; a mixin on `Player.updatePlayerPose` (HEAD, cancellable) keeps
   the **server** pose at SWIMMING every tick so the server hitbox is 0.6 tall and never flickers.
6. De-target sweep in a 64-block box: every `Mob` whose `getTarget() == player` → `setTarget(null)`;
   every `Warden` → `clearAnger(player)`. Repeated every 20 ticks while downed.
7. Boss bar (`ServerBossEvent`, RED, PROGRESS) shown to **all online players**:
   `"<name> is downed · 2:59 · right-click to revive"`, progress = remaining/180 000.
8. Chat broadcast: `"<name> is downed in <dimension> at x, y, z — 3:00 to revive them."`
9. Private chat to the downed player: how to be revived, plus a clickable **[Give up]** link that
   runs `/hc giveup` (see **Give up** below).

**While downed** (`ServerTickEvents.END_SERVER_TICK`, per downed **record**, online or not; D22):
- Offline and expired → `recordOfflineBleedOut`: deaths+1, downed cleared, `pendingKill = true`,
  audit `DEATH … bled out offline, kill owed on next join`, bar removed, line and quiet bell to
  everyone online. Online and expired → `bleedOut`, retried every tick until vanilla accepts it.
- The bar is refreshed once a second from the record alone, so it keeps counting for an offline
  player. A record owing a kill → `bleedOut` while its player is online.
- `ALLOW_DAMAGE` returns `false` unless the source `is(BYPASSES_INVULNERABILITY)`.
- Health pinned at 1 (natural regen would otherwise refill hearts visually).
- Boss bar text/progress updated each second.
- `AttackBlockCallback`, `UseBlockCallback`, `AttackEntityCallback`, `UseEntityCallback`,
  `UseItemCallback` return `FAIL` for a downed actor, with an action-bar notice
  `"You are downed and cannot do that."` (see §12-D7 for why the hand is not faked empty).
- If `now >= downedUntilMs` (wall clock) → **bleed out**: online `bleedOut` (retried until vanilla
  accepts it), offline `recordOfflineBleedOut`.

**Untargetable**: mixin `Player.canBeSeenAsEnemy` → false while downed (covers every
`TargetingConditions`/`NearestAttackableTargetGoal`/brain sensor path plus `Mob.setTarget`
validation); mixin `Warden.canTargetEntity` → false for a downed player (covers anger, sniffing,
sonic boom). The invisibility effect is **not** used.

**Bleed-out** (`DownedManager.bleedOut`): `player.hurtServer(level, damageSources().genericKill(),
Float.MAX_VALUE)`. `generic_kill` bypasses invulnerability, totems and armour, with one exception
verified in the 26.2 bytecode: `ServerPlayer.isInvulnerableTo` returns true for **every** source until
`connection.hasClientLoaded()`, and while changing dimension. `bleedOut` therefore asks
`isInvulnerableTo` first, returns false without touching state, and the tick retries; it never clears
the downed state on failure (D23). The vanilla death message reads "<name> died", and the mod's own
chat lines add the detail. This is the only downed → dead route besides bypass damage.

**Clock pause** (0.1.3, D21): `ReviveManager.tryStart` calls `DownedManager.pauseClock` right after
the channel is registered → `HeartStateService.pauseDowned` stores `downedUntilMs − now` (at least
1 ms) in `downedPausedMs` and audits `DOWNED_PAUSED`. A break, cancel or disconnect calls
`resumeClock` by UUID (the downed player may already be offline) → `resumeDowned` sets
`downedUntilMs = now + downedPausedMs`, clears the pause, audits `DOWNED_RESUMED`. A success
clears the whole downed state as before. Two repairs cover a crash mid-channel: the join handler
resumes a paused record when no channel exists, and `DownedManager.tick` does the same every tick.
The boss bar reads "clock paused while being revived" and `/hc info` shows "(clock paused)".

**Revive audio** (0.1.2, D20): while a channel runs, a note-block pling plays at the target every
`Rules.REVIVE_NOTE_INTERVAL_TICKS` = 8 ticks with pitch `ReviveRules.notePitch(elapsed)`: whole
semitones across the note-block range 0.5–2.0 over the 160 ticks, so the scale climbs two octaves
and the 20th note lands on completion. Completion plays an amethyst chime (1.0, pitch 1.2) plus a
light level-up (0.6, pitch 1.5), replacing the 0.1.0 totem sound; a break plays a note-block bass
at pitch 0.5. All are world sounds (`Level.playSound`), so bystanders hear them too.

**Give up** (`/hc giveup` → `/hc giveup confirm`, v0.1.1): a downed player alone on the server
need not wait out the clock. Step 1 (`/hc giveup`) only prints the price ("You would respawn with
N hearts …") and a clickable **[Confirm: give up]** link; step 2 (`/hc giveup confirm`) calls
`DownedManager.giveUp`, which refuses unless the player is downed, calls `bleedOut` first, and only
if the kill landed audits `GAVE_UP` with the time that was left and broadcasts `"<name> gave up and
accepted the death."` (0.1.4: a refused kill announces nothing; the command answers "try again in a
moment" instead of "You are not downed"). Because it is the
same `generic_kill` path, the penalty is exactly a bleed-out. Both steps are `run_command` click
events on vanilla chat components; the client sends them as ordinary unsigned commands.

**Teardown** (`DownedManager.clear`, idempotent, used by revive, bleed-out, death, join):
`downedUntilMs = 0` (persisted), glow off, pose reset to STANDING, remove both movement
modifiers, remove boss bar, cancel any revive channel on this target.

**Crash / relog recovery**: the downed flag is in `SavedData`. On join, a downed record →
`reenter` (re-applies all presentation from state) regardless of expiry: the kill cannot land at JOIN
time (client not loaded), so the next tick resolves an expired clock once it can. A record owing a
kill, or not downed → `setGlowingTag(false)` and remove the downed modifiers (stale leftovers from a
crash mid-downed); the tick lands the owed kill once the client has loaded.

**Timer is wall-clock time** (0.1.4, D24), so logging out, an empty (paused) server or a restart
never stop it. Records from 0.1.3 or older carry a world-tick deadline (`legacyDeadlineTicks`); overworld
game time is persisted, so the first tick converts it exactly (`withLegacyClockConverted`: remaining
ticks × 50 ms), and a clock that had already run out under 0.1.3 rules owes the death (offline →
`recordOfflineBleedOut`). A paused remainder is a duration and converts at 50 ms per tick on load.

### Revive channel (`ReviveChannel`)

- Started by a living (not downed, not spectator, not dead) player right-clicking the downed
  player (`UseEntityCallback`). Entry requirement: reviver `foodLevel >= 6`, else
  `"You need at least 6 food points (3 drumsticks) to revive someone."` and nothing starts.
- **One reviver per target**: a second candidate gets `"<name> is already being revived by <other>."`
- Duration 160 ticks. Progress shown on both players' action bars every 4 ticks:
  `"Reviving <name>… 62 %"` / `"<reviver> is reviving you… 62 %"`.
- Hunger drain: **direct and deterministic** (§12-D9). At ticks 27, 53, 80, 107, 133, 160 one
  point is removed from each player: saturation first (−1.0), else food level (−1).
  Total 6 points each.
- Breaks (channel discarded, must restart from zero) when: reviver moved > 2.0 blocks from the
  start position; either player took damage (`AFTER_DAMAGE`, or health decreased since last tick
  for the reviver); either player's food level reached 0; either disconnected; reviver became
  downed/dead/spectator; target is no longer downed; reviver–target distance > 4 blocks.
  Both players get an action-bar line saying why.
- Success: `DownedManager.clear(target)`, `setHealth(getMaxHealth())`, sound
  `TOTEM_USE` (volume 0.6) at the target, broadcast `"<reviver> revived <name>!"`.
  **No death counted, nothing lost.**

---

## 6. Tier 2 — True death

`ServerLivingEntityEvents.AFTER_DEATH` (player only; fires at `ServerPlayer.die` TAIL, i.e. only
when vanilla actually committed the death — a totem save never reaches it):

1. `DownedManager.clear(player)` (no-op when not downed).
2. `deaths++` → persisted, flushed, audited, scoreboard.
3. Broadcast to others (short): `"<name> died · deaths 2 · 6 hearts"` /
   `"<name> is now on their final life."` / `"<name>'s run has ended."`
4. Quiet server-wide cue: `BELL_RESONATE` at volume 0.3, pitch 0.6 to everyone except the victim.

`ServerPlayerEvents.AFTER_RESPAWN(old, new, alive)` with `alive == false`:

1. `HealthService.normalize(new, rec)` — applies the lower cap.
2. `new.setHealth(new.getMaxHealth())`.
3. If **not** eliminated: `BELL_RESONATE`, pitch 0.6, volume 1.0, `SoundSource.MASTER`, to that
   player only (`ClientboundSoundPacket` at the player's position).
4. Chat to that player (persistent, scrollable):
   ```
   You died. Deaths: 2 · Max health reduced to 6 hearts.
   Craft a Crimson Heart to restore a level. Next restoration costs 1 Heart.
   One more death puts you on your final life.
   ```
   Third line: absent on death #1; the sentence above on death #2; on death #3
   `"You are now on your final life. The next death is permanent."`
   On elimination (death #4) the message is instead
   `"Your run is over. You may spectate the world."`
5. Scoreboard already synced in step 2 of AFTER_DEATH.

**Respawn button.** One server-side mixin:

- `ServerGamePacketListenerImpl.handleClientCommand`: `@WrapOperation` on
  `MinecraftServer.isHardcore()` → returns `eliminated(player)`. Non-eliminated players respawn in
  survival; eliminated players get stock hardcore spectator behaviour.

The `hardcore` flag in the login packet is **left to vanilla** (v0.1.1; see D11). On a hardcore
world every player therefore sees hardcore hearts, and every death screen reads "Game over!" with a
**Spectate world** button. That button sends the same `PERFORM_RESPAWN` as "Respawn", so the mixin
above decides the outcome: survival respawn unless eliminated. The client derives both the heart
texture and the death-screen wording from this one flag, and no vanilla packet separates them.

Respawn location is vanilla's (bed/anchor, else world spawn) — untouched.

---

## 7. Tier 3 — Final life & elimination

- `deaths == 3`: alive at 4 hearts, downed never triggers, a held totem works as in vanilla.
- `deaths >= 4`: eliminated — spectator on respawn, stays spectator on join. No world-end
  condition; the world continues.
- A Crimson Heart consumed at `deaths == 3` → `deaths == 2` → `finalLife()` false again,
  automatically, because it is derived state.

---

## 8. The Crimson Heart

Recipe (in-jar datapack `data/hcheart/recipe/crimson_heart.json`), exactly as in the brief:
E N E / N ★ N / E N E with E = echo shard, N = netherite scrap, ★ = nether star; result nether
star ×1 with `custom_data {hcheart: true}`, red non-italic name "Crimson Heart", rarity epic,
glint override. Balance rationale (echo shards are the real price) is preserved verbatim in README.

Detection: `stack.is(Items.NETHER_STAR) && customData.copyTag().getBooleanOr("hcheart", false)`.

**Texture (0.1.2, D19).** The result also carries `custom_model_data {strings: ["hcheart:crimson_heart"]}`
(`HeartItem.MODEL_KEY`). The optional client resource pack in `resourcepack/` (format 88.0, built by
the Gradle `resourcePack` task with STORED entries and fixed timestamps, so the zip and its SHA-1 are
reproducible) overrides `assets/minecraft/items/nether_star.json` with a `minecraft:select` on
`minecraft:custom_model_data` index 0: the key → `hcheart:item/crimson_heart` (an `item/generated`
model over a 16×16 sprite cut from the owner's reference `docs/assets/heart.png`, a 9×9 pixel grid
scaled ×2), anything else → the vanilla star. Without the pack the client draws the vanilla star.
The join handler stamps the key onto Hearts that predate 0.1.2 (inventory and ender chest);
identity remains the custom_data tag alone. `ResourcePackTest` keeps pack and mod in step.

**Sink guards** (a Heart must never silently become a plain nether star):
- `BeaconMenu$PaymentSlot.mayPlace` → false for a Heart (the brief's beacon guard; the slot lives
  in the menu, not the block entity, in 26.2).
- `Ingredient.test` → false for a Heart (§12-D10): otherwise a Heart + glass + obsidian crafts a
  beacon, and a Heart can be fed back into the Crimson Heart recipe. Hearts are never a crafting
  ingredient anywhere.

**Consume handler** (`UseItemCallback`, right-click):

```
if !isHeart(held)               → PASS
if level.isClientSide           → SUCCESS      (never reached server-side; kept for symmetry)
if not ServerPlayer             → PASS
if downed                       → FAIL          (interaction lock handles this first anyway)
if cooldowns.isOnCooldown(held) → FAIL
if deaths == 0                  → FAIL + "You are already at full health."
count = hearts in main inventory + offhand
if count < restoreCost()        → FAIL + "You need N Crimson Hearts to restore a level (you have M)."
mutate: deaths--, restoresUsed++            → persist + flush + audit + scoreboard   (FIRST)
HealthService.normalize; setHealth(max)
remove restoreCost() Hearts: held stack first, then the rest of the inventory     (SECOND)
cooldowns.addCooldown(held, 20)
sound BEACON_POWER_SELECT pitch 1.2; ParticleTypes.HEART ×12 around the player
broadcast "<name> consumed 2 Crimson Hearts and is back to 8 hearts. Their next restoration costs 3."
return SUCCESS
```

Hearts are tradeable; nothing prevents it. Optional craft broadcast: hidden advancement with a
`minecraft:recipe_crafted` criterion whose reward function `tellraw`s the server and revokes itself.

---

## 9. Join handler (the most important code)

`ServerPlayerEvents.JOIN(player)` (fires after `placeNewPlayer` completes):

1. `rec = service.getOrCreate(uuid)`; refresh `lastKnownName`.
2. `HealthService.normalize(player, rec)` — **unconditionally** resets base to 20 and reapplies
   the penalty (repairs skillux's `base 18`).
3. Spectator rescue: `if (!rec.eliminated() && player.isSpectator())` → `setGameMode(SURVIVAL)`,
   teleport via `findRespawnPositionAndUseSpawnBlock(false, DO_NOTHING)` + `teleport(transition)`,
   `setHealth(getMaxHealth())`, `removeAllEffects()`. Order: gamemode, teleport, health, effects.
4. Downed resolution: `downedUntilMs > 0` → `reenter` (expiry is resolved by the tick once the
   client has loaded; a kill cannot land at JOIN time); owing a kill, or not downed → clear stale
   glow and downed modifiers.
5. `ScoreboardService.sync(rec)`.

`ServerPlayerEvents.LEAVE` cancels any revive channel involving the player and removes them as a
viewer of every bar. Their own bar, clock and state stay: the tick keeps counting them down (D22).

---

## 10. Admin commands (`/hc`, permission level 2 unless noted)

| Command | Behaviour |
|---|---|
| `/hc info` (anyone) | own record |
| `/hc giveup` (anyone, downed only) | step 1: print the penalty and a clickable **[Confirm: give up]** link; no effect. |
| `/hc giveup confirm` (anyone, downed only) | step 2: `DownedManager.giveUp` → audit `GAVE_UP`, broadcast, `bleedOut`. "You are not downed." otherwise. |
| `/hc info <player>` | deaths, restores used, max hearts, next Heart cost, final-life / eliminated / downed (with seconds left). `<player>` is a `GameProfileArgument`, so **offline players resolve** via the server's name cache. |
| `/hc set <player> <deaths> <restores>` | write both counters (clamped ≥ 0), clear downed, normalise if online (incl. spectator rescue / elimination), scoreboard, audit. |
| `/hc reset all` | wipe the map, normalise every online player (base 20, full health, survival if spectator, downed teardown), clear the scoreboard objective scores, audit. Offline players are handled on their next join. |
| `/hc give <player> [count]` | give Crimson Hearts (1–64) to an online player. |

## 11. Scoreboard

Objective `deaths_hc` (dummy, display name "Deaths") created if missing on `SERVER_STARTED` and
set to the `list` slot. Written with `deaths` on every state change, by player **name**
(`ScoreHolder.forNameOnly(lastKnownName)`) so offline corrections show too.

## 12. Decisions and deviations from the brief (with reasons)

- **D1 · Mojang mappings.** Forced: Yarn does not exist for 26.2. All identifiers translated (§13).
- **D2 · `eliminated()` vs `finalLife()`.** The brief's literal `finalLife()` (deaths ≥ 3) would put
  a player in spectator on their *third* death, contradicting "three true deaths puts a player at
  4 hearts and on final life". Downed gating uses `finalLife()`; spectator/respawn gating and the
  join-time spectator rescue use `eliminated()` (deaths ≥ 4).
- **D3 · Death counted in `AFTER_DEATH`, not in `ALLOW_DEATH`.** Returning `true` from
  `ALLOW_DEATH` does not guarantee a death: vanilla's totem check runs afterwards. Counting there
  could charge a death that never happened — the worst direction of error. `AFTER_DEATH` fires only
  when `die()` ran.
- **D4 · Totem interaction.** Downed takes precedence for non-final-life players (as the brief
  states), so totems are never consumed before final life; on final life a totem works normally.
  Bleed-out uses `generic_kill`, which bypasses totems, so bleed-out is unpreventable.
- **D5 · Transient attribute modifiers** instead of persistent ones (§4).
- **D6 · Bypass damage is not blocked while downed.** A downed player in the void, or `/kill`ed by
  an admin, dies immediately as a true death instead of falling for three minutes. This matches the
  brief's own Tier 2 list ("`/kill`, void, admin damage").
- **D7 · No fake-empty hand while downed.** Faking hotbar slots client-side requires spoofing
  inventory packets and re-syncing on exit; a desync there is an inventory bug on a hardcore
  server. Instead every use attempt is refused server-side with an action-bar notice. The client may
  briefly animate an eat attempt; nothing is consumed.
- **D8 · The downed player's own first-person camera** stays at standing height in open areas,
  because the vanilla client computes its own pose locally. Everyone else sees them prone, the
  server hitbox is prone, and in a 1-block gap the client itself crawls. Forcing the client camera
  needs a client mod or the fake-barrier-block trick, deliberately not implemented.
- **D9 · Deterministic hunger drain** (six discrete points per player at fixed ticks) instead of
  exhaustion arithmetic, so the amount is exact and unit-testable.
- **D10 · Ingredient guard** added alongside the beacon guard (§8).
- **D11 · Hardcore flag in the login packet** is left to vanilla (v0.1.1). v0.1.0 drove it from
  `finalLife()` so non-final players got a "Respawn" button and normal hearts, at the cost of a
  stale flag until relog. After the first live session the owner preferred the hardcore heart
  texture for everyone; the price is the "Game over! / Spectate world" wording on every death
  screen. The button still respawns non-eliminated players in survival (D2). No forced reconnects.
- **D12 · Synchronous saved-data flush on every mutation** (§3) — closes the "inventory saved on
  disconnect but state lost on crash" window, which is the bad direction.
- **D13 · Boss bar visible to everyone**, not just the downed player, so rescuers see the clock.
- **D14 · Revive also breaks when reviver and target drift > 4 blocks apart** (the target can crawl).
- **D15 · `/hc info` without argument** is usable by anyone for their own record.
- **D16 · README heart ladder** corrected from "10 → 7 → 4" to the brief's "10 → 8 → 6 → 4".
- **D17 · `/hc giveup` → `/hc giveup confirm`** (v0.1.1). A downed player alone on the server can
  end the wait. Two steps so a stray click in chat cannot cost two hearts; the confirm reuses
  `bleedOut`, so there is exactly one downed → dead route and the penalty is identical. Audit event
  `GAVE_UP`. Requested by the owner after the first live session.
- **D18 · Downed crawl speed −75 %** (0.1.2), down from the brief's −50 %: the owner found half
  speed too fast in play. One constant (`Rules.DOWNED_SPEED_MULTIPLIER`); the gametest pins the
  effective value at 0.025 (a quarter of the 0.1 base).
- **D19 · Heart texture via an optional server resource pack** (0.1.2). A vanilla client cannot
  draw a sprite it does not have, so the choice was: a `custom_model_data` key + resource pack
  (chosen: declining the pack degrades to the vanilla star, nothing gameplay-relevant changes), or a
  player-head item with a heart skin (rejected: 3D cube, placeable, changes the item type and every
  guard). `item_model` was rejected because a client without the pack would render the missing model.
- **D20 · Revive audio** (0.1.2): rising note-block scale, bass on break, chime on completion (§5).
  Pure pitch arithmetic lives in `ReviveRules` and is unit-tested; the gametest captures the
  `ClientboundSoundPacket`s on the mock connection and checks 20 non-decreasing pitches ending at 2.0.
- **D21 · Revive channel pauses the bleed-out clock** (0.1.3). Owner report: a friend reached a
  downed player with under 8 s left and the player bled out mid-channel. The pause is persisted as a
  remainder (`downedPausedMs`) rather than by moving the deadline every tick, so it costs two
  commits per channel instead of one per tick, survives crashes, and stays idempotent (pause/resume
  are no-ops when already in that state). Resume uses the UUID so a disconnecting downed player's
  clock keeps running as before.
- **D22 · The tick walks the records, not the online players** (0.1.4). Owner report: a downed
  friend disconnected, the boss bar froze for everyone else, and the clock was only settled when he
  came back. The 0.1.3 loop iterated `getPlayers()`, so an offline downed player was invisible to
  it. Now every record is visited: bars refresh from the record, and an expiry while offline
  counts the death at that moment (`recordOfflineBleedOut`, `pendingKill = true`, announced to
  everyone). The vanilla kill is owed on the next join and `AFTER_DEATH` settles it with
  `applyPendingKill` instead of counting. `pendingKill` zeroes any clock on construction so one
  bleed-out can never become two deaths. Chosen over "count at join" so the tab list, `/hc info`
  and the audit log are right at the moment it happened, and over an in-memory flag so a restart
  between expiry and join cannot lose it.
- **D23 · A failed kill never clears state** (0.1.4). Owner report: a player who logged in with an
  expired clock was "self-revived". The server log showed `Bleed-out kill did not take … clearing
  downed state` at the join second. Root cause, verified in the 26.2 bytecode:
  `ServerPlayer.isInvulnerableTo` is true for every damage source, `generic_kill` included, until
  the client reports loaded; the join handler runs before that, so the kill at join could never
  land, and the 0.1.3 fallback then cleared the state. Now `bleedOut` checks `isInvulnerableTo`
  first, returns false, and the tick retries; the join handler never resolves expiry itself. The
  gametest joins a player with the client unloaded, exactly as production does.
- **D24 · Wall-clock deadline** (0.1.4). The brief chose world time so "logging out does not pause
  it", but world time also stops while the server is empty (vanilla pause-when-empty, present in
  the owner's logs: "Server empty for 60 seconds, pausing") and while it is down. The intent is
  "the clock continues regardless", so the deadline is now epoch milliseconds. Tests use a clock
  seam (`HeartStateService.setClock`) because the gametest server ticks faster than real time.
  Migration: a 0.1.3 deadline is world ticks, and world time is persisted, so it is converted
  exactly on the first tick rather than reset (a review of the first draft caught the "cannot
  convert" premise as false).
- **Absorption / Health Boost** stack on top of the penalty (vanilla semantics; temporary, costly,
  and blocking them would need extra mixins for marginal benefit).

## 13. Name translation (brief → Mojang 26.2)

`ServerPlayerEntity`→`ServerPlayer` · `EntityAttributes.MAX_HEALTH`→`Attributes.MAX_HEALTH` ·
`EntityAttributeModifier`→`AttributeModifier` · `addPersistentModifier`→`addPermanentModifier`
(we use `addTransientModifier`) · `PersistentState`→`SavedData` · `PersistentStateType`→`SavedDataType` ·
`ServerBossBar`→`ServerBossEvent` · `EntityPose`→`Pose` · `setGlowing`→`setGlowingTag` ·
`MobEntity`→`Mob` · `LivingEntity.canTarget`→`canBeSeenAsEnemy` · `SoundCategory`→`SoundSource` ·
`SoundEvents.BLOCK_BELL_RESONATE`→`SoundEvents.BELL_RESONATE` · `BLOCK_BEACON_POWER_SELECT`→`BEACON_POWER_SELECT` ·
`NbtComponent`→`CustomData` · `DataComponentTypes`→`DataComponents` · `ItemCooldownManager`→`ItemCooldowns` ·
`ActionResult`→`InteractionResult` · `GameMode`→`GameType` · `Identifier.of`→`Identifier.fromNamespaceAndPath` ·
`PlayerManager`→`PlayerList` · `ServerPlayNetworkHandler`→`ServerGamePacketListenerImpl` ·
`BeaconBlockEntity` slot validation → `BeaconMenu$PaymentSlot.mayPlace` · `HungerManager`→`FoodData` ·
`ServerPlayConnectionEvents.JOIN`→`ServerPlayerEvents.JOIN` (player-level, fires after full load).

## 14. Testing

**Unit tests (plain JUnit 5, `src/test/java`, no Minecraft imports):** `PlayerRecord` derived
values across the full ladder; `restoreCost` escalation; `DeathRules.decide` for every branch
(bypass, downed, final life, normal); bleed-out expiry arithmetic; `ReviveRules` drain schedule
(exactly 6 points, saturation before food) and break conditions; codec round-trip via
`fabric-loader-junit` (NbtOps, no registries needed).

**Gametests (`fabric-gametest-api-v1`, own `gametest` source set, run by `runGameTest`, wired into
`check`/`build`):** with a survival mock `ServerPlayer` placed through `PlayerList.placeNewPlayer`
(so the real join handler runs):
join normalisation (base 18 → 20, penalty modifier, clamp) · spectator rescue on join ·
downed entry from lethal damage (alive at 1 HP, flag persisted, glow, pose, modifiers) ·
damage immunity while downed · bypass damage kills a downed player · zombie loses and cannot
re-acquire the target; warden `canTargetEntity` false · revive success (no death counted, full
health, teardown) · revive refused below 6 food · single reviver lock · revive breaks on reviver
movement, on damage, on food 0 · bleed-out at expiry (death counted, respawn at 8 hearts) ·
`/hc giveup` prompt is harmless, `/hc giveup confirm` kills and counts, refused when not downed ·
a revive channel pauses a 2 s clock a minute past its deadline, a break resumes it and the player
bleeds out later (clock seam) · a paused record resumes on join and by the tick repair · an expired
clock at join with the client unloaded keeps the state and the kill lands once loaded, counted once ·
an offline expiry counts the death, keeps the bar counting, tells everyone, and the rejoin settles
the owed kill exactly once (own batch, clock seam) · ≤ 0.1.3 tick-based files load as legacy clocks
and a paused remainder converts (JUnit) · record pause/resume arithmetic
and codec round trip incl. pre-0.1.3 files (JUnit) ·
revive plays 20 rising notes and a chime (captured packets) · crafted Heart carries the model key ·
join stamps pre-0.1.2 Hearts in inventory and ender chest · `ResourcePackTest` (JUnit) ties the pack
to `HeartItem.MODEL_KEY`, format 88 and a 16×16 sprite ·
final-life lockout (deaths=3 → lethal damage kills; deaths=4 → `PERFORM_RESPAWN` yields spectator) ·
Heart consumption and escalating cost, refusal when short, restore from final life ·
beacon slot rejects a Heart, `Ingredient` rejects a Heart, recipe loads and yields a Heart ·
1-block corridor: downed player not suffocating.

**Proof of correctness** to be reported: unit test results, the gametest run log (JUnit XML), a
successful `build` producing the remapped jar, and the server log showing the datapack recipe
loaded without parse errors.

## 15. Deliverables (repo layout)

```
build.gradle, gradle.properties, settings.gradle, gradle/wrapper/*
src/main/java/com/fracturedhardcore/hcheart/
  HcHeartMod.java                      entrypoint, wiring
  core/  PlayerRecord, Rules, DeathRules, ReviveRules      (pure Java)
  state/ HeartState (SavedData+codec), HeartStateService, AuditLog
  health/HealthService
  downed/DownedManager, DownedEvents, ReviveChannel, ReviveManager
  death/ DeathEvents, Messages
  heart/ HeartItem
  join/  JoinHandler
  command/HcCommand
  scoreboard/ScoreboardService
  mixin/ PlayerMixin (canBeSeenAsEnemy, updatePlayerPose), WardenMixin,
         ServerGamePacketListenerImplMixin, BeaconPaymentSlotMixin, IngredientMixin
src/main/resources/fabric.mod.json, hcheart.mixins.json,
  data/hcheart/recipe/crimson_heart.json,
  data/hcheart/advancement/crafted_crimson_heart.json, data/hcheart/function/crafted.mcfunction
resourcepack/ (0.1.2) pack.mcmeta, pack.png, assets/minecraft/items/nether_star.json,
  assets/hcheart/models/item/crimson_heart.json, assets/hcheart/textures/item/crimson_heart.png
src/test/java/...                      unit tests
src/gametest/java/..., src/gametest/resources/fabric.mod.json
scripts/backup.sh                      rolling world backup (rcon save-off/save-all/save-on)
README.md                              player-facing rules, admin guide, known limitations
```
