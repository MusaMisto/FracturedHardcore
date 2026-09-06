<p align="center">
  <img src="docs/assets/logo.png" width="340" alt="Fractured Hardcore logo: three pixel hearts, one whole, one cracking, one shattered, above the words Fractured Hardcore">
</p>

<h1 align="center">Fractured Hardcore</h1>

<p align="center"><em>Three lives. Make them count.</em></p>

<p align="center">
  <a href="https://github.com/MusaMisto/FracturedHardcore/actions/workflows/build.yml"><img src="https://github.com/MusaMisto/FracturedHardcore/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.2-62B47A?logo=minecraft&logoColor=white" alt="Minecraft 26.2">
  <img src="https://img.shields.io/badge/Fabric%20Loader-0.19.5-DBB69B" alt="Fabric Loader 0.19.5">
  <img src="https://img.shields.io/badge/Fabric%20API-0.159.0%2B26.2-DBB69B" alt="Fabric API 0.159.0+26.2">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/Gradle-9.5.1-02303A?logo=gradle&logoColor=white" alt="Gradle 9.5.1">
  <img src="https://img.shields.io/badge/Loom-1.17-1F6FEB" alt="Fabric Loom 1.17">
  <img src="https://img.shields.io/badge/Mappings-Mojang%20official-5865F2" alt="Mojang mappings">
  <img src="https://img.shields.io/badge/Side-server--only-2EA043" alt="server-side only">
  <img src="https://img.shields.io/badge/Tests-30%20unit%20%C2%B7%2037%20gametest-2EA043" alt="Tests: 30 unit, 37 gametest">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/MusaMisto/FracturedHardcore" alt="license"></a>
  <img src="https://img.shields.io/github/last-commit/MusaMisto/FracturedHardcore" alt="last commit">
</p>

**Fractured Hardcore** (mod id `hcheart`) is a **server-side** [Fabric](https://fabricmc.net) mod for Minecraft Java
**26.2** that replaces vanilla hardcore's instant permadeath with a three-tier survival system. Lethal damage makes you
*downed* instead of dead; a friend can revive you. If nobody does, you die for real and your maximum health drops one
step down the ladder **10 → 8 → 6 → 4 hearts**. At four hearts you are on your final life. The fourth death is permanent.
A craftable **Crimson Heart** buys one step back, at a price that only ever goes up.

Players connect with a completely vanilla client. Every piece of UI is a boss bar, action bar, chat line, glow,
pose, particle or sound. Built for a private five-player hardcore server where the worst possible bug deletes a friend's
character, so the code favours **boring and verifiable** over clever.

## Table of contents

- [How it plays](#how-it-plays)
  - [Tier 1: Downed](#tier-1-downed)
  - [Revive](#revive)
  - [Tier 2: True death](#tier-2-true-death)
  - [Tier 3: Final life and elimination](#tier-3-final-life-and-elimination)
  - [The Crimson Heart](#the-crimson-heart)
- [Quick start for server admins](#quick-start-for-server-admins)
- [Commands](#commands)
- [Files the mod writes](#files-the-mod-writes)
- [For contributors and agents](#for-contributors-and-agents)
  - [Toolchain](#toolchain)
  - [Build and test](#build-and-test)
  - [Project layout](#project-layout)
  - [Architecture](#architecture)
  - [Runtime flows](#runtime-flows)
  - [Vanilla touchpoints](#vanilla-touchpoints)
  - [Data model and persistence](#data-model-and-persistence)
  - [Invariants (do not break these)](#invariants-do-not-break-these)
  - [Tuning constants](#tuning-constants)
  - [Testing](#testing)
  - [Upgrading Minecraft or Fabric](#upgrading-minecraft-or-fabric)
  - [Troubleshooting](#troubleshooting)
  - [Known limitations](#known-limitations)
  - [Documentation map and README maintenance](#documentation-map-and-readme-maintenance)
- [Contributing](#contributing)
- [License](#license)

## How it plays

### Tier 1: Downed

When lethal damage arrives and you are **not** on your final life, you do not die. You are **downed**:

- Health is pinned at 1 HP. You lie prone (a 1-block-high hitbox), glow through walls, crawl at a quarter of normal speed, cannot jump.
- You are **immune to all ordinary damage**. Hostile mobs drop you as a target and cannot re-acquire you. The Warden
  ignores you (its anger, sniffing and sonic boom all route through the same check).
- You cannot attack, place, break, use items or interact with anything. Attempts get an action-bar notice.
- A red boss bar, visible to everyone online, shows who is downed and the time left: **180 seconds of world time**, so
  logging out does not pause it. When it runs out you **bleed out** and die for real.
- **The clock stops while someone is reviving you.** The moment a revive channel starts, the bar freezes with the time
  left; if the channel breaks, it resumes from exactly there. Reaching a friend with two seconds to spare is enough.
- Alone on the server, or nobody can reach you? Click **[Give up]** in the downed message or run `/hc giveup`, then
  confirm. You bleed out immediately and take the normal death penalty: it is the same death path as the clock running
  out, nothing more and nothing less.
- Damage that bypasses invulnerability (the void, `/kill`) still kills you outright. That is a true death.
- If the server crashes or you relog while downed, the state is restored from disk: still downed if time remains,
  bled out immediately if not.

### Revive

A living player **right-clicks** the downed player and stays within **2 blocks** of where they started for **8 seconds**.

- The reviver needs at least **6 food points** to begin, or the channel refuses with a message.
- Both players lose **6 food points** over the channel (saturation first, then food), one point at a time.
- The channel breaks and must restart from zero if: the reviver moves more than 2 blocks from the start position, either
  player takes damage, either player's food reaches zero, the two drift more than 4 blocks apart, the reviver dies or is
  downed, or either player disconnects. Both players are told why.
- **One reviver per target.** A second player gets "already being revived by …".
- Starting the channel **pauses the downed player's clock**; a break resumes it with the time that was left, so the channel
  itself never costs the downed player time. The remainder is stored on disk, so a crash mid-revive keeps it.
- You hear the channel: a note-block note every 0.4 s climbing two octaves over the 8 seconds, a low bass note if it
  breaks, and a chime (an amethyst ring with a light level-up sparkle) when it completes. Everyone nearby hears them.
- On success: **zero penalty**. No death counted, no hearts lost, health refilled, the chime, a server-wide line.

### Tier 2: True death

Bleeding out, giving up, or a bypass source is a true death. The client shows the **hardcore death screen** ("Game over!"
with a **Spectate world** button) because the world really is hardcore and your hearts carry the hardcore look at all
times. The button respawns you normally: you appear at your bed or world spawn and your maximum health drops one step.
Only an eliminated player actually becomes a spectator. A low bell plays for you; a quieter one for everyone
else. Chat tells you your death count, the new cap, **what a Crimson Heart costs right now**, and how far you are from
your final life. The tab list shows everyone's death count.

### Tier 3: Final life and elimination

At three deaths you sit at **4 hearts permanently**. The downed safety net is gone: lethal damage kills you. A held Totem
of Undying still works as in vanilla, which makes totems final-life insurance. The **fourth death ends your run**: you
respawn as a spectator (stock hardcore behaviour), everyone is told, and the world continues for the others. There is no
world-end condition.

### The Crimson Heart

A Nether Star carrying `minecraft:custom_data {hcheart: true}`, named **Crimson Heart**, epic rarity, glinting. With the
optional **resource pack** (Quick start, step 5) it is drawn as a crimson hardcore heart instead of a star; without it, the
glinting star. The pack picks the model from a `custom_model_data` string the mod stamps on every Heart, so identity
never depends on the texture. Shaped recipe:

```
E N E      ★  Nether Star      ×1
N ★ N      N  Netherite Scrap  ×4
E N E      E  Echo Shard       ×4
```

Right-click to consume. It restores **one level** (deaths −1) and refills your health. **The price escalates and never
resets**: the first restoration you ever perform costs 1 Heart, the second 2, the third 3, and so on. The price tracks how
many times *you* have restored, not how many hearts you have. Hearts are drawn from anywhere in your inventory. They are
tradeable: handing your only Heart to the weakest player is a free choice, not an obligation. Consuming a Heart at three
deaths lifts final life again, because final life is derived from the death count.

Why this recipe: Echo Shards are Ancient City loot only and cannot be farmed at scale, so they set the real price; Nether
Stars collapse to an AFK grind once someone builds a wither-skeleton farm. If the recipe ever needs rebalancing, adjust the
netherite and leave the shards alone. Hearts can never be lost: they are rejected by beacon payment slots and are not
accepted as an ingredient in **any** recipe (including the beacon recipe and the Heart recipe itself).

## Quick start for server admins

| Requirement | Version |
|---|---|
| Minecraft Java dedicated server | 26.2 |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | 0.159.0+26.2 or newer for 26.2 |
| Java | 25 |

1. Download `fractured-hardcore-<version>.jar` from the [releases](https://github.com/MusaMisto/FracturedHardcore/releases)
   or the latest [build artifact](https://github.com/MusaMisto/FracturedHardcore/actions/workflows/build.yml), and drop it
   into `mods/` next to Fabric API.
2. Start the server. The log line `Loaded Fractured Hardcore state for N player(s)` confirms the mod is active.
3. Run **`/hc reset all`** once before the first session. It wipes every record, sets everyone online to 10 hearts,
   and pulls spectators back to survival at full health.
4. Set up backups (see [Files the mod writes](#files-the-mod-writes)). Hourly during sessions is the minimum.
5. Optional, recommended: the **Crimson Heart texture**. Every release ships `fractured-hardcore-resourcepack-<version>.zip`.
   The release asset URL works directly as the pack URL. Add to `server.properties` (SHA-1 is printed in the release notes;
   the pack is built reproducibly, so `shasum -a 1` on your copy gives the same value):

   ```properties
   resource-pack=https://github.com/MusaMisto/FracturedHardcore/releases/download/v0.1.3/fractured-hardcore-resourcepack-0.1.3.zip
   resource-pack-sha1=1223e50c22fd2ca07e44f9c48c13ef697c86ca2d
   resource-pack-prompt={"text":"Fractured Hardcore: draws the Crimson Heart as a heart. Optional."}
   require-resource-pack=false
   ```

   Players who accept see the heart; players who decline see the vanilla star. Nothing in the game depends on it. Hearts
   crafted before 0.1.2 get the texture the next time their owner logs in (inventory and ender chest); a Heart sitting in
   a chest gets it once someone logs in carrying it. Use the zip from the same release as the jar.

Existing worlds are repaired automatically on each player's next login. The join handler resets the max-health **base
value** to 20 (undoing any earlier `/attribute … base set`), reapplies the correct penalty from stored state, returns
players stranded in spectator by stock hardcore to survival at their bed or spawn, and clears stale glow or crawl left
over from a crash. No `player.dat` editing, ever.

## Commands

| Command | Permission | Effect |
|---|---|---|
| `/hc info` | anyone | Your own record: deaths, restores used, max hearts, next Heart cost, status. |
| `/hc info <player>` | op (level 2) | Same for any player, **online or offline** (resolves through the server's name cache). |
| `/hc giveup` | anyone, only while downed | Step 1 of 2: explains the penalty and shows a clickable **[Confirm: give up]** link. Nothing happens yet. |
| `/hc giveup confirm` | anyone, only while downed | Step 2 of 2: bleed out now, counted exactly like the clock running out. Refused with "You are not downed." otherwise. |
| `/hc set <player> <deaths> <restores>` | op | Manual correction. Clears downed state. If the player is online, health cap, game mode (spectator ↔ survival) and scoreboard are re-derived immediately. |
| `/hc reset all` | op | Wipes every record, normalises and heals everyone online, rescues spectators. Offline players are fixed on their next join. |
| `/hc give <player> [count]` | op | Gives 1–64 Crimson Hearts. |

## Files the mod writes

| Path | Purpose |
|---|---|
| `<world>/data/hcheart/players.dat` | Persistent state, vanilla `SavedData` (NBT). Flushed **synchronously** on every mutation. Included in normal world saves and backups. |
| `logs/hcheart-audit.log` | Append-only audit trail: ISO timestamp, UUID, name, event (`INIT`, `DEATH`, `RESTORE`, `DOWNED`, `DOWNED_CLEARED`, `DOWNED_PAUSED`, `DOWNED_RESUMED`, `REVIVED`, `GAVE_UP`, `SET`, `RESET`, `GIVE`, `RENAME`, `RESTORE_SHORT`), details. Read this when someone says they were shorted at 2 a.m. |
| Scoreboard objective `deaths_hc` | Display name "Deaths", shown in the `list` slot (tab list). Mirrors each player's death count. |

`scripts/backup.sh <server-dir> <backup-dir> [keep]` performs a rolling `save-off` → `save-all flush` → `tar` → `save-on`
cycle over rcon and rotates old archives. It needs `enable-rcon=true` and `rcon.password` in `server.properties` and
`mcrcon` on the PATH. A cron line for hourly backups is in the script header.

## For contributors and agents

Everything below describes the code **as it is on `main`**. If you change the code, change this file in the same commit.

### Toolchain

| Component | Value | Notes |
|---|---|---|
| Minecraft | 26.2 | `gradle.properties` → `minecraft_version` |
| Mappings | **Mojang official** | Loom's default; there is no Yarn for 26.2. Class names are `ServerPlayer`, `Attributes.MAX_HEALTH`, `SavedData`, … |
| Fabric Loader | 0.19.5 | bundles MixinExtras 0.5.x (`@WrapOperation` is available) |
| Fabric API | 0.159.0+26.2 | events, gametest API, command API |
| Fabric Loom | 1.17-SNAPSHOT | Gradle plugin; also provides the `runGameTest` task via `fabricApi.configureTests` |
| Gradle | 9.5.1 (wrapper) | configuration cache disabled (Loom) |
| Java | 25 | `options.release = 25`; Minecraft 26.2 requires it |
| Tests | JUnit 5.12 + fabric-loader-junit, fabric-gametest-api-v1 | |

JDK 25 is not the macOS default. On the author's machine it is `brew install openjdk@25`, then
`export JAVA_HOME=/opt/homebrew/opt/openjdk@25`.

### Build and test

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build            # compile + unit tests + headless gametest server; jar and resource-pack zip land in build/libs/
./gradlew resourcePack     # just the resource-pack zip (STORED, fixed timestamps: byte-identical everywhere)
./gradlew test             # unit tests only (fast)
./gradlew runGameTest      # gametests only; log in build/run/gameTest/logs/latest.log
./gradlew runServer        # a dev dedicated server with the mod (accept the EULA in run/ first)
./gradlew genSources       # decompiled, Mojang-mapped Minecraft sources for your IDE
```

`build` is green only when all three stages pass. The gametest stage prints `All N required tests passed :)`. CI runs the
same command on every push and pull request ([`.github/workflows/build.yml`](.github/workflows/build.yml)) and uploads
the jar and the resource-pack zip as one artifact.

### Project layout

```
.
├── build.gradle, gradle.properties, settings.gradle   Loom build; versions live in gradle.properties
├── src/main/java/com/fracturedhardcore/hcheart/
│   ├── HcHeart.java              MOD_ID, LOGGER, id(path)
│   ├── HcHeartMod.java           ModInitializer: lifecycle wiring and event registration order
│   ├── Services.java             per-server record: state, downed, revive (created SERVER_STARTING, dropped SERVER_STOPPED)
│   ├── core/                     PURE JAVA, no Minecraft imports, unit-tested
│   │   ├── Rules.java            every tunable constant
│   │   ├── PlayerRecord.java     immutable record + derived values (maxHearts, finalLife, eliminated, restoreCost, …)
│   │   ├── DeathRules.java       lethal-damage outcome, damage blocking, spectator/rescue decisions
│   │   └── ReviveRules.java      drain schedule, entry/completion checks, break reasons
│   ├── state/
│   │   ├── HeartCodecs.java      Codec<PlayerRecord>, Codec<Map<UUID, PlayerRecord>>
│   │   ├── HeartState.java       SavedData (UUID → PlayerRecord), SavedDataType, file hcheart/players.dat
│   │   ├── HeartStateService.java  THE ONLY mutation gateway: commit = put + flush + audit + scoreboard
│   │   └── AuditLog.java         append-only text log
│   ├── health/HealthService.java   max-health base reset + one transient penalty modifier + clamp
│   ├── join/JoinHandler.java       runs on every join; RespawnService.java rescues spectators
│   ├── downed/
│   │   ├── DownedManager.java    enter / reenter / clear / bleedOut / giveUp / tick / boss bars / de-target sweep
│   │   ├── DownedEvents.java     ALLOW_DEATH, ALLOW_DAMAGE, interaction lock
│   │   ├── ReviveManager.java    one channel per target; tick; break handling; success
│   │   ├── ReviveEvents.java     UseEntityCallback → tryStart
│   │   └── Text.java             mm:ss and coloured text helpers
│   ├── death/
│   │   ├── DeathEvents.java      AFTER_DEATH (count) and AFTER_RESPAWN (apply cap, sounds, messages, bar viewers)
│   │   ├── Messages.java         every player-facing string
│   │   └── Sounds.java           play a sound to one player
│   ├── heart/HeartItem.java        detection, creation, model-key stamping, counting/removal, UseItemCallback consume handler
│   ├── command/HcCommand.java      /hc tree and applyLive
│   ├── scoreboard/ScoreboardService.java  deaths_hc objective
│   └── mixin/                    five small mixins (see Vanilla touchpoints)
├── src/main/resources/
│   ├── fabric.mod.json, hcheart.mixins.json, assets/hcheart/icon.png
│   └── data/hcheart/             recipe/crimson_heart.json, advancement/crafted_crimson_heart.json, function/crafted.mcfunction
├── src/test/java/…               JUnit: PlayerRecordTest, DeathRulesTest, ReviveRulesTest, HeartCodecsTest, ResourcePackTest
├── src/gametest/                 gametest source set (own mod id hcheart-gametest)
│   ├── java/…/gametest/          TestPlayers (harness), Hc (service access), 6 test classes
│   └── resources/                fabric.mod.json (entrypoints), test_environment/isolated.json
├── resourcepack/                 optional client pack: pack.mcmeta (format 88), items/nether_star.json select, heart model, 16×16 sprite
├── docs/superpowers/specs/       design spec with every decision and deviation from the original brief
├── docs/superpowers/plans/       the implementation plan the code was built from
├── docs/assets/                  logo, heart.png (the sprite reference)
├── scripts/backup.sh             rolling rcon backup
└── .github/workflows/build.yml   CI
```

### Architecture

```mermaid
flowchart TB
    subgraph Vanilla["Minecraft 26.2 (Mojang mappings)"]
        V1["LivingEntity.hurtServer / die"]
        V2["PlayerList.placeNewPlayer / respawn"]
        V3["ServerGamePacketListenerImpl"]
        V4["Player / Warden / Ingredient / BeaconMenu"]
    end
    subgraph Fabric["Fabric API events"]
        E1["ALLOW_DEATH · ALLOW_DAMAGE · AFTER_DEATH"]
        E2["JOIN · LEAVE · AFTER_RESPAWN"]
        E3["Use*/Attack* callbacks · END_SERVER_TICK · commands"]
    end
    subgraph Mod["hcheart"]
        M1["DownedEvents / ReviveEvents / DeathEvents / HeartItem / HcCommand / JoinHandler"]
        M2["DownedManager · ReviveManager · HealthService · RespawnService"]
        M3["HeartStateService — the only writer"]
        M4[(HeartState SavedData)]
        M5["AuditLog · ScoreboardService"]
        MX["5 mixins"]
        C["core: PlayerRecord · DeathRules · ReviveRules · Rules"]
    end
    V1 --> E1 --> M1
    V2 --> E2 --> M1
    V4 -. targeting, pose, ingredient, slot .-> MX
    V3 -. spectator switch .-> MX
    E3 --> M1
    M1 --> M2 --> M3
    M1 --> M3
    M3 --> M4
    M3 --> M5
    M1 & M2 & MX --> C
```

Three layers, dependencies pointing inward:

1. **`core`** is plain Java. It owns every rule (`DeathRules.onLethalDamage`, `ReviveRules.check`, …) and every derived
   value on `PlayerRecord`. It has no Minecraft imports so it is fully unit-testable.
2. **State** (`HeartStateService` over `HeartState`) is the only place a `PlayerRecord` is written. Every mutation is one
   `commit`: put in the map, `saveAndJoin()` the saved data to disk, append an audit line, sync the scoreboard.
3. **Game integration** (events, managers, mixins, commands) reads the record, asks `core` what to do, and calls the
   service. Live attributes (health cap, movement, jump, glow, pose) are **derived** from the record and reapplied on join,
   respawn and every relevant change. Nothing is adjusted incrementally.

`Services` is created in `SERVER_STARTING` (saved-data storage exists then) and the scoreboard objective is ensured in
`SERVER_STARTED`. Every event handler null-checks `HcHeartMod.services()` and behaves as vanilla when it is null.

### Runtime flows

```mermaid
stateDiagram-v2
    [*] --> Alive
    Alive --> Downed: lethal damage while deaths < 3 (ALLOW_DEATH false, health 1)
    Alive --> Dead: lethal damage on final life, or bypass damage (void, /kill)
    Downed --> Alive: revived by an 8 s channel, no penalty
    Downed --> Dead: bleed-out after 180 s or /hc giveup confirm (generic_kill), or bypass damage
    Dead --> Alive: AFTER_DEATH deaths+1, respawn with cap max(4, 10 − 2·deaths)
    Dead --> Eliminated: deaths reaches 4, respawn as spectator
    Alive --> Alive: Crimson Heart consumed, deaths−1, restoresUsed+1
```

**Join** (`JoinHandler.onJoin`, `ServerPlayerEvents.JOIN`, fires at `PlayerList.placeNewPlayer` RETURN):
`getOrCreate` record and refresh name → `HealthService.normalize` (base 20, penalty modifier, clamp) → rescue from
spectator unless eliminated → resume a clock left paused by a channel that no longer exists (crash mid-revive) → downed:
expired ? `bleedOut` : `reenter`; not downed: `clearPresentation` → add the player to existing boss bars → stamp any
pre-0.1.2 Heart with the model key → scoreboard sync.

**Lethal damage** (`DownedEvents`, `ServerLivingEntityEvents.ALLOW_DEATH`): Fabric redirects the *second*
`isDeadOrDying()` in `LivingEntity.hurtServer`, i.e. before the totem check and `die()`. Health is already ≤ 0 here.
`DeathRules.onLethalDamage(record, source.is(BYPASSES_INVULNERABILITY))` returns `TRUE_DEATH` (bypass, already downed,
or final life) → we return `true` and vanilla continues (totem check, then `die()`), or `ENTER_DOWNED` → `DownedManager.enter`
persists `downedUntilTick = overworld game time + 3600` **first**, then sets health to 1, glow, prone pose, speed −75 %
and jump −100 % transient modifiers, clears mob targets and Warden anger within 48 blocks, creates the boss bar, broadcasts.

**While downed** (`DownedManager.tick`, `END_SERVER_TICK`): resume a paused clock that no channel holds (crash repair); bleed
out when expired, which a paused clock never is; otherwise pin health at 1, reassert glow
and pose, every 20 ticks re-sweep targets and refresh the bar. `ALLOW_DAMAGE` returns false for non-bypass damage.
Five interaction callbacks return `FAIL` for a downed actor (registered **before** the revive and Heart handlers so the lock
wins). `/hc giveup` (chat prompt with a confirm link) then `/hc giveup confirm` → `DownedManager.giveUp`: refuse unless
downed, audit `GAVE_UP`, broadcast, `bleedOut`. Commands are not covered by the interaction lock, so a downed player can run them.

**Revive** (`ReviveEvents` → `ReviveManager.tryStart`, then `ReviveManager.tick`): a `Channel` records reviver, target,
start position, elapsed ticks and last `hurtTime` of both, and `tryStart` pauses the target's clock (`DownedManager.pauseClock`
→ `DOWNED_PAUSED`, the ticks left are stored on the record). Each tick: compute `ReviveRules.check(...)`; on a break reason,
end (resume the clock: `DOWNED_RESUMED`, deadline = now + ticks left; low bass note) and notify; else `elapsed++`, drain one point from each player at ticks 27/53/80/107/133/160,
show progress every 4 ticks, play a note-block note every 8 ticks (`ReviveRules.notePitch`: whole semitones from 0.5 to
2.0), and at 160 → `DownedManager.clear`, refill health, chime, broadcast, audit `REVIVED`.

**True death** (`DeathEvents`): `AFTER_DEATH` fires at `ServerPlayer.die` TAIL, so the death is committed and a totem save
never reaches it → `DownedManager.clear` → `recordDeath` (deaths+1, downed cleared) → line and quiet bell to everyone else.
`AFTER_RESPAWN` fires at `PlayerList.respawn` TAIL (`alive == false` for deaths; `true` is an End-portal trip and only swaps
boss-bar viewers) → `normalize` + refill → eliminated: title, subtitle, chat; else: bell to that player, chat lines.
One mixin decides the client-facing side: the `PERFORM_RESPAWN` spectator switch = `eliminated()`. The login packet's
`hardcore` flag is left to vanilla, so on a hardcore world every player sees hardcore hearts and the "Game over! /
Spectate world" death screen; that button still respawns a non-eliminated player in survival.

**Crimson Heart** (`HeartItem.consume`, `UseItemCallback`): not downed, not on cooldown, `canRestore()`, enough hearts →
`HeartStateService.restore` (persist + flush **first**) → `normalize` + refill → remove `restoreCost()` hearts (held stack
first, then any slot) → 20-tick cooldown on nether stars → sound, particles, broadcast.

### Vanilla touchpoints

Everything the mod depends on in Minecraft or Fabric API, so an upgrade can be checked against this list.

| Kind | Target | Purpose |
|---|---|---|
| Event | `ServerLivingEntityEvents.ALLOW_DEATH` (redirect of 2nd `isDeadOrDying()` in `LivingEntity.hurtServer`) | enter downed / allow death |
| Event | `ServerLivingEntityEvents.ALLOW_DAMAGE` | immunity while downed |
| Event | `ServerLivingEntityEvents.AFTER_DEATH` (at `ServerPlayer.die` TAIL) | count the death |
| Event | `ServerPlayerEvents.JOIN` / `LEAVE` / `AFTER_RESPAWN` | join handler, cleanup, apply cap after respawn |
| Event | `UseItemCallback`, `UseBlockCallback`, `UseEntityCallback`, `AttackBlockCallback`, `AttackEntityCallback` | interaction lock, revive start, Heart consume |
| Event | `ServerTickEvents.END_SERVER_TICK`, `ServerLifecycleEvents.*`, `CommandRegistrationCallback` | ticking, lifecycle, `/hc` |
| Mixin | `Player.canBeSeenAsEnemy()` HEAD, cancellable | untargetable while downed; every `TargetingConditions`/`Mob.setTarget` path funnels through it |
| Mixin | `Player.updatePlayerPose()` HEAD, cancellable | keep the server pose `SWIMMING` while downed |
| Mixin | `Warden.canTargetEntity(Entity)` HEAD, cancellable | Warden anger/attacks ignore downed players |
| Mixin | `ServerGamePacketListenerImpl.handleClientCommand` → `@WrapOperation` on `MinecraftServer.isHardcore()` | spectator only when eliminated |
| Mixin | `BeaconMenu$PaymentSlot.mayPlace` HEAD, cancellable | reject Hearts (safety net; vanilla's payment tag has no nether star) |
| Mixin | `Ingredient.test(ItemStack)` HEAD, cancellable | Hearts are never a recipe ingredient |
| API | `SavedDataType`, `MinecraftServer.getDataStorage().computeIfAbsent/saveAndJoin`, `DataFixTypes.SAVED_DATA_COMMAND_STORAGE` (opaque type, never rewritten by fixers) | persistence |
| API | `AttributeInstance.setBaseValue/addTransientModifier/removeModifier(Identifier)`, `Attributes.MAX_HEALTH/MOVEMENT_SPEED/JUMP_STRENGTH` | health cap, downed movement |
| API | `ServerBossEvent`, `ServerPlayer.sendSystemMessage(Component, overlay)`, `ClientboundSoundPacket`, `ClientboundSetTitleTextPacket`, `Level.playSound` | UI, revive sounds |
| API | `DataComponents.CUSTOM_MODEL_DATA` (`strings[0]` = `hcheart:crimson_heart`) | texture selection by the optional resource pack; identity stays `CUSTOM_DATA` |
| API | `ServerPlayer.findRespawnPositionAndUseSpawnBlock`, `teleport(TeleportTransition)`, `setGameMode` | spectator rescue |
| API | `GameProfileArgument` / `NameAndId`, `Commands.hasPermission(PermissionCheck)`, `Permissions.COMMANDS_GAMEMASTER` | commands |
| Data | `data/hcheart/recipe/crimson_heart.json` (`crafting_shaped`, component result), advancement `recipe_crafted` + reward function | recipe, craft broadcast |

### Data model and persistence

```java
record PlayerRecord(int deaths, int restoresUsed, long downedUntilTick, long downedPausedTicks, String lastKnownName)
int     maxHearts()   = max(4, 10 − 2·deaths)        boolean finalLife()  = deaths ≥ 3
boolean eliminated()  = deaths ≥ 4                    int     restoreCost() = restoresUsed + 1
boolean isDowned()    = downedUntilTick > 0           // absolute overworld game-time tick
boolean isDownedPaused() = isDowned() && downedPausedTicks > 0   // ticks left while a revive channel holds the clock
```

- Stored in `HeartState` (a `SavedData`) as NBT `{players: {"<uuid>": {deaths, restores_used, downed_until, downed_paused, name}}}`; every
  field is `optionalFieldOf` with a default, negative counters fail to parse rather than load. `lastKnownName` lets
  `/hc info`, the scoreboard and the audit log work for offline players.
- The file is `<world>/data/hcheart/players.dat`. `DataFixTypes.SAVED_DATA_COMMAND_STORAGE` is used because vanilla
  registers it as an opaque (`DSL::remainder`) type in every schema, so no data fixer will ever rewrite our fields.
- `HeartStateService.flush()` calls `saveAndJoin()` synchronously after every commit. Mutations are rare (deaths, restores,
  downed enter/exit, admin commands) so this is cheap, and it closes the crash window in the safe direction: a crash can
  give a player a free item, never take one away.

### Invariants (do not break these)

1. **Only `HeartStateService` mutates a `PlayerRecord`.** No other class touches the map.
2. **Persist before consuming.** State is committed and flushed before hearts are removed or anything irreversible happens.
3. **Never call `setHealth(0)` and never adjust health incrementally.** Recompute from the record with
   `HealthService.normalize`, which is idempotent and safe to call repeatedly.
4. **Deaths are counted in `AFTER_DEATH`, never in `ALLOW_DEATH`.** Returning `true` from `ALLOW_DEATH` does not guarantee a
   death (a totem may fire). Counting early would charge a death that never happened, the worst direction of error.
5. **Bleed-out kills with `generic_kill`** (bypasses invulnerability, totems, armour). The clock expiring and
   `/hc giveup confirm` both go through `DownedManager.bleedOut`; it is the only downed → dead route besides bypass damage.
6. **Attribute modifiers are transient with stable ids** (`hcheart:heart_penalty`, `hcheart:downed_speed`,
   `hcheart:downed_jump`). Nothing of ours is written to `player.dat`; join and respawn are the single source of truth.
7. **The base max-health value is reset to 20 on every join and respawn**, unconditionally.
8. **Interaction lock is registered before revive and Heart handlers** so a downed actor is refused first
   (`HcHeartMod.onInitialize` order: `DownedEvents`, `ReviveEvents`, `HeartItem`, `HcCommand`, `DeathEvents`).
9. **Every handler tolerates `services() == null`** (before `SERVER_STARTING`, after `SERVER_STOPPED`) by deferring to vanilla.
10. **Every behaviour change ships with a test** and a matching README update.
11. **A Heart is identified by `custom_data {hcheart: true}` only.** The name, glint and model key are cosmetic and may be
    missing on old items; `HeartItem.isHeart` must never look at them.
12. **The revive pause is persisted, never held in memory.** A channel stores the ticks left on the record
    (`downedPausedTicks`); a crash mid-revive resumes from that remainder on the next join or tick. Only
    `HeartStateService.pauseDowned/resumeDowned` change it, and every fresh clock or clear resets it.

### Tuning constants

All in `core/Rules.java`. Changing them changes unit-test expectations too.

| Constant | Value | Meaning |
|---|---|---|
| `BASE_HEARTS` / `FLOOR_HEARTS` / `HEARTS_LOST_PER_DEATH` | 10 / 4 / 2 | the ladder |
| `FINAL_LIFE_DEATHS` / `ELIMINATION_DEATHS` | 3 / 4 | thresholds |
| `DOWNED_DURATION_TICKS` | 3600 (180 s) | bleed-out clock, overworld game time |
| `REVIVE_DURATION_TICKS` | 160 (8 s) | channel length |
| `REVIVE_MIN_FOOD` / `REVIVE_FOOD_COST` | 6 / 6 | entry requirement / points drained from each player |
| `REVIVE_MAX_REVIVER_DRIFT` / `REVIVE_MAX_SEPARATION` | 2.0 / 4.0 blocks | break conditions |
| `DOWNED_SPEED_MULTIPLIER` / `DOWNED_JUMP_MULTIPLIER` | −0.75 / −1.0 (`ADD_MULTIPLIED_TOTAL`) | quarter speed (was half until 0.1.2), no jump |
| `HEART_USE_COOLDOWN_TICKS` | 20 | duplicate-packet guard |
| `REVIVE_NOTE_INTERVAL_TICKS` | 8 | one rising note per 8 ticks, 20 over the channel |

### Testing

**Unit tests** (`src/test/java`, JUnit 5, no server): the whole `core` package, the codec round-trip, and `ResourcePackTest`
(the pack under `resourcepack/` uses format 88, selects on `HeartItem.MODEL_KEY`, and ships a 16×16 sprite). Run with
`./gradlew test`. They are the specification of the rules; if you change a rule, change the test first.

**Gametests** (`src/gametest`, `fabric-gametest-api-v1`): a headless `GameTestServer` boots with the mod and runs every
`@GameTest` method listed in `src/gametest/resources/fabric.mod.json`. They exercise the real event chain, mixins,
datapack and commands. Six classes, 37 tests:

| Class | Covers |
|---|---|
| `JoinGameTests` | base-value repair, penalty application, spectator rescue, eliminated players stay spectator |
| `DownedGameTests` | downed entry, immunity, bypass kills, zombie loses/cannot reacquire target, Warden ignores, bleed-out, 1-block-gap hitbox, relog recovery, paused clock resumes on join and by the tick repair |
| `ReviveGameTests` | success with exact hunger drain, hungry reviver refused, single-reviver lock, breaks on move / damage / starvation, 20 rising notes and the chime reach the reviver (packet capture), a channel pauses the clock and a break resumes it with the time that was left (the player still bleeds out later) |
| `DeathGameTests` | cap after respawn, Respawn keeps survival until elimination, elimination → spectator, totem only on final life |
| `HeartGameTests` | recipe loads and crafts (with the model key), join stamps pre-0.1.2 Hearts, Hearts never an ingredient, beacon slot guard, escalating cost, refusals, lifting final life |
| `CommandGameTests` | `/hc set`, `/hc reset all` (isolated batch), `/hc give`, `/hc info`, `/hc giveup` (prompt alone is harmless, confirm kills and counts, refused when not downed) |

Harness facts you need before writing a gametest (all encoded in `TestPlayers`):

- Mock players are real `ServerPlayer`s pushed through `PlayerList.placeNewPlayer` with a dead-end netty `Connection`, so
  the real join handler runs. They spawn survival, fed, on a stone block at the requested structure-relative position.
- 26.2 keeps a player **invulnerable until its client reports loaded** (`connection.hasClientLoaded()`); the harness sends
  `ServerboundPlayerLoadedPacket` for you. Without it no damage lands.
- A mock connection is not registered with the network listener, so `Player.tick()` would never run (no cooldowns, food,
  pose updates). The harness ticks `connection.tick()` every test tick via `helper.onEachTick`.
- Tests run **concurrently** in one batch. Anything global (like `/hc reset all`) must run in its own environment:
  `@GameTest(environment = "hcheart-gametest:isolated")`, defined in `src/gametest/resources/data/hcheart-gametest/test_environment/isolated.json`.
- Always `TestPlayers.leave(player)` in a `finally` block; use `helper.runAfterDelay` for anything that needs ticks, and
  raise `maxTicks` accordingly.
- `TestPlayers.drainSent(player)` returns every packet the server wrote to that mock client since the last drain (the
  `EmbeddedChannel` has no encoder, so you get raw packet objects). Use it to assert sounds, titles or messages.
- To add a test class, register it under `fabric-gametest` in the gametest `fabric.mod.json`.

### Upgrading Minecraft or Fabric

1. Check what exists: `https://meta.fabricmc.net/v2/versions/loader/<mc>` and the Fabric API versions on Modrinth; the
   example mod at `github.com/FabricMC/fabric-example-mod/tree/<mc>` shows the Loom/Gradle/Java versions to use.
2. Bump `gradle.properties` (and `java` in `fabric.mod.json`, `options.release`, `compatibilityLevel` if Java changed).
3. `./gradlew genSources`, then re-verify every row of [Vanilla touchpoints](#vanilla-touchpoints) against the decompiled
   sources: mixin method names and descriptors, the hardcore checks in `PlayerList.placeNewPlayer` and
   `ServerGamePacketListenerImpl.handleClientCommand`, `Player.canBeSeenAsEnemy`, `Warden.canTargetEntity`, the
   `SavedDataType` constructor, and where Fabric injects `ALLOW_DEATH`/`AFTER_DEATH`/`AFTER_RESPAWN`.
4. Bump the resource pack: `min_format`/`max_format` in `resourcepack/pack.mcmeta` to the new `pack_version.resource_major`
   (from `version.json` inside the client jar) and the matching assertion in `ResourcePackTest`; confirm the `minecraft:select`
   item-model property `minecraft:custom_model_data` still exists.
5. `./gradlew build`. Mixin failures show up at gametest boot; behaviour drift shows up as test failures.
6. Update the badges, the toolchain table and this section.

### Troubleshooting

| Symptom | Check |
|---|---|
| Player has the wrong max health | `/hc info <player>`, then have them relog: the join handler re-derives everything. Look for `/attribute` in the server history. |
| Someone claims a Heart vanished | `logs/hcheart-audit.log`: `RESTORE` lines carry cost and counters; `RESTORE_SHORT` means fewer hearts were found than expected after the state was already committed. |
| "Respawn" button shown but player became spectator | Expected after the fourth death in the same session as the third (see Known limitations). |
| Gametests fail with `was 20.0` after lethal damage | The mock client was not marked loaded; use `TestPlayers.join`. |
| Timing-based gametests fail randomly | A concurrently running test mutated global state; isolate it with the `isolated` environment. |
| Recipe missing in game | Server log at datapack load: search for `hcheart`. The gametest `recipeLoadsAndCraftsAHeart` guards this. |
| Heart shows as a star for one player | They declined the pack, or the server has no `resource-pack` line, or `resource-pack-sha1` does not match the zip (`shasum -a 1`); on a mismatch the client discards the download. |
| Heart shows as a purple-and-black box | The pack and the jar are from different releases; use the zip shipped with the jar. |
| `/hc info` says "clock paused" but nobody is reviving | Self-heals within a tick while the player is online and on their next join otherwise; the audit log shows `DOWNED_RESUMED … (repair)`. |
| `Failed to parse saved data for 'SavedDataType[hcheart:players]'` | The file is damaged; vanilla starts fresh. Restore `players.dat` from backup or rebuild records from the audit log with `/hc set`. |

### Known limitations

- The client derives **both** the heart texture and the death screen from one `hardcore` flag sent at login, and the mod
  leaves that flag as vanilla (true on a hardcore world). So everyone sees hardcore hearts, and every death screen reads
  "Game over!" with a **Spectate world** button. The button still respawns a non-eliminated player in survival; only an
  eliminated player becomes a spectator. No vanilla packet separates the two, and the flag cannot change without a reconnect.
- A downed player's **own camera** stays at standing height in open areas because the vanilla client computes its own pose;
  everyone else sees them prone, the server hitbox is prone, and in a 1-block gap the client crawls too.
- A server crash in the middle of a revive leaves the clock paused until the downed player next joins; it then resumes with
  the time that was left, in the player's favour.
- The heart texture needs the optional resource pack; a client without it sees the glinting star. A Heart left in a chest
  keeps the old look until a player carrying it logs in.
- Absorption and Health Boost stack on top of the reduced cap, as in vanilla.
- Totems of Undying are only consumed on the final life; before that, being downed takes precedence.

### Documentation map and README maintenance

- `docs/superpowers/specs/2026-09-05-fractured-hardcore-design.md`: the design, every decision, and every deviation from
  the original brief with its reason. Add to its "Decisions and deviations" section when you make one.
- `docs/superpowers/plans/2026-09-05-fractured-hardcore.md`: the task-by-task plan the code was built from (historical).
- `CONTRIBUTING.md`: the short checklist.

> **Reminder for agents and contributors:** this README is the single source of truth for how the codebase works. If you
> change behaviour, commands, files, constants, hook points, tests, dependencies or the toolchain, **update the relevant
> section of `README.md` in the same change** so it always reflects the current version of the repository. A pull request
> that changes code without touching the README when the README describes that code is incomplete.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md), keep changes small, add a test for every
behaviour change, run `./gradlew build`, and update this README. Use conventional commit messages
(`feat(scope): …`, `fix(scope): …`, `docs: …`, `test: …`).

## License

[MIT](LICENSE) © 2026 Musa Misto. Not affiliated with Mojang or Microsoft.
