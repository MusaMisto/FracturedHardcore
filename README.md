# Fractured Hardcore (`hcheart`)

A **server-side** Fabric mod for Minecraft Java **26.2** that replaces vanilla hardcore's instant permadeath with a
three-tier survival system, plus a craftable item that partially undoes death penalties. Players connect with a
completely vanilla client; every piece of UI is boss bar, action bar, chat, glow, pose, particles and sounds.

Heart ladder: **10 → 8 → 6 → 4 hearts**. Three true deaths put you on your final life at 4 hearts. The fourth death is
permanent (spectator).

## How it plays

### Tier 1 — Downed
When lethal damage arrives and you are *not* on your final life, you don't die. You are **downed**:

- You drop to 1 HP, lie prone (1-block-high hitbox), glow through walls, move at half speed and cannot jump.
- You are **fully immune to damage** and hostile mobs (including the Warden) stop targeting you.
- You cannot attack, use items or blocks, or interact with anything.
- A red boss bar shows everyone who is downed and how long is left: **180 seconds**, measured in world time, so logging
  out does not pause it. When it expires you **bleed out** and die for real.
- Damage that bypasses invulnerability (the void, `/kill`) still kills you outright.

**Revive:** a living player right-clicks you and stays within 2 blocks for **8 seconds**. The reviver needs at least
6 food points to begin; both players lose roughly 6 food points (saturation first) over the channel. The channel breaks
and must restart if the reviver moves away, either player takes damage, either player's food hits zero, or you drift more
than 4 blocks apart. One reviver per target: first to start wins. **A successful revive costs nothing — no death, no
hearts lost.**

### Tier 2 — True death
Bleeding out (or a bypass source) is a true death. You see the normal death screen with a **Respawn** button, respawn at
your bed or world spawn, and your maximum health drops by one heart pair. You hear a low bell; everyone else hears a
quieter one. The chat tells you your death count, your new cap, and **what a Crimson Heart costs right now**.

### Tier 3 — Final life
At three deaths you sit at 4 hearts permanently. The downed safety net is gone: lethal damage kills you (a held Totem of
Undying still works, as in vanilla). The fourth death ends your run — stock hardcore spectator. The world continues for
everyone else.

### The Crimson Heart
A Nether Star carrying `minecraft:custom_data {hcheart: true}`, shaped recipe:

```
E N E      ★  Nether Star     ×1
N ★ N      N  Netherite Scrap ×4
E N E      E  Echo Shard      ×4
```

Right-click to consume. It restores one level (deaths −1) and refills your health. **The price escalates and never
resets**: the first restoration ever costs 1 Heart, the second 2, the third 3… The price tracks how many times *you* have
restored, not how many hearts you have. Hearts are drawn from anywhere in your inventory, and they are tradeable.

Why this recipe: Echo Shards are Ancient City loot only and unfarmable at scale, which sets the real price; a player who
wants to climb out of 4 hearts must run Ancient Cities at 4 hearts. If it ever needs rebalancing, adjust the netherite
and leave the shards alone.

Hearts can never be lost to a beacon or used as an ingredient in any other recipe.

## Admin guide

1. Install on a Fabric 26.2 dedicated server together with Fabric API `0.159.0+26.2` or newer. Java 25 is required.
2. Run **`/hc reset all`** once before the first session. It wipes every record, normalises anyone online and pulls
   spectators back to survival at full health.
3. Nothing else to do. The join handler repairs existing damage automatically on each player's next login:
   - a player whose max-health **base value** was altered with `/attribute` (base is reset to 20 on every join),
   - players **stranded in spectator** by stock hardcore rules (returned to survival at their bed or spawn),
   - stale glow / crawl left over from a crash.

| Command | Who | What |
|---|---|---|
| `/hc info` | anyone | your own record |
| `/hc info <player>` | op | deaths, restores used, max hearts, next Heart cost, status. Works for **offline** players. |
| `/hc set <player> <deaths> <restores>` | op | manual correction (online or offline); live state is re-derived immediately |
| `/hc reset all` | op | wipe everything, normalise everyone online |
| `/hc give <player> [count]` | op | hand out Crimson Hearts |

Files:

- State: `<world>/data/hcheart/players.dat` (vanilla saved data, flushed to disk synchronously on every change).
- Audit log: `logs/hcheart-audit.log` — every death, restore, downed entry/exit, admin command, with timestamp and UUID.
- Scoreboard: objective `deaths_hc` ("Deaths") is created and shown in the tab list; it mirrors each player's deaths.

**Back up the world** before deploying and at least hourly during sessions. `scripts/backup.sh` does a rolling
`save-off` / `save-all flush` / `tar` / `save-on` cycle over rcon; a cron line is in the header comment.

## Known limitations (protocol-level, deliberate)

- The client learns the "hardcore" flag (which controls the **Respawn** vs **Spectate world** button and the heart
  texture) only when it logs in. A player who reaches final life mid-session keeps the Respawn button until they relog;
  if they die again in that same session the button still says Respawn, but the server puts them in spectator and tells
  them their run is over. After a relog they see hardcore hearts and the stock "Game over" screen.
- A downed player's own first-person camera stays at standing height in open areas because the vanilla client computes
  its own pose. Everyone else sees them prone, the server hitbox is prone, and inside a 1-block gap the client crawls too.
- Absorption (golden apples) and Health Boost stack on top of the reduced cap, as in vanilla.
- Totems of Undying are only consumed on the final life; before that, being downed takes precedence.

## Design notes

- State is two integers and a timestamp per player, stored world-side and keyed by UUID; all live attributes are derived
  from it and reapplied on join and respawn. Health is never adjusted incrementally.
- One service class performs every mutation: write, synchronous flush, audit line, scoreboard.
- A death is counted only after vanilla has committed it (`AFTER_DEATH`), never when it is merely about to happen, so a
  totem save can never be charged as a death.
- Six small mixins cover what Fabric events cannot: mob targeting (`Player.canBeSeenAsEnemy`), the Warden
  (`canTargetEntity`), the server pose (`updatePlayerPose`), the login-packet hardcore flag, the respawn→spectator switch,
  the beacon payment slot and `Ingredient.test`.
- Full rationale and every deviation from the original brief: `docs/superpowers/specs/2026-09-05-fractured-hardcore-design.md`.

## Building and testing

```
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

`build` compiles against Mojang mappings, runs the JUnit tests for the pure rule engine, and starts a headless game-test
server that runs the integration suite (downed entry, immunity, de-targeting, revive and every break condition,
bleed-out, respawn caps, final life, elimination, Heart consumption and escalation, recipe, guards, join repair,
commands). The jar is written to `build/libs/`.
