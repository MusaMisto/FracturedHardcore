# Fractured Hardcore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `hcheart` server-side Fabric mod for Minecraft 26.2: downed state, true death with a shrinking heart cap, final life / elimination, and the Crimson Heart restore item — with unit tests for the pure rules and gametests for the integration layer.

**Architecture:** A pure-Java `core` package (records + rule functions, no Minecraft imports) is wrapped by one mutation gateway (`HeartStateService`, backed by a `SavedData` flushed synchronously on every change). Fabric events (`ALLOW_DEATH`, `ALLOW_DAMAGE`, `AFTER_DEATH`, `AFTER_RESPAWN`, `JOIN`, tick, interaction callbacks) drive `DownedManager`, `ReviveManager`, death handling and the Heart item; six small mixins cover what events cannot reach (targeting, pose, hardcore UI flag, respawn→spectator, beacon slot, ingredient matching).

**Tech Stack:** Java 25, Gradle 9.5.1, Fabric Loom 1.17 (Mojang mappings), Fabric Loader 0.19.5, Fabric API 0.159.0+26.2, JUnit 5 + fabric-loader-junit, fabric-gametest-api-v1.

**Spec:** `docs/superpowers/specs/2026-09-05-fractured-hardcore-design.md`

## Global Constraints

- Minecraft `26.2`, Fabric Loader `>=0.19.5`, Fabric API `0.159.0+26.2`, Java `>=25`, Loom `1.17-SNAPSHOT`, Gradle `9.5.1`.
- Mojang official mappings (Loom default; no `mappings` dependency). Yarn does not exist for 26.2.
- Mod id and namespace: `hcheart`. Root package: `com.fracturedhardcore.hcheart`. Display name: "Fractured Hardcore".
- Server-side behaviour only; `fabric.mod.json` `"environment": "*"`; no new items/blocks registered.
- Health ladder `10 → 8 → 6 → 4` hearts; `finalLife = deaths >= 3`; `eliminated = deaths >= 4`; `restoreCost = restoresUsed + 1`.
- Downed lasts `3600` ticks of overworld game time; revive channel `160` ticks; reviver needs `>= 6` food; drain `6` points from each.
- Every state mutation goes through `HeartStateService` and is flushed to disk before any inventory is touched.
- Never call `setHealth(0)`; never adjust health incrementally.
- JDK for builds: `/opt/homebrew/opt/openjdk@25` (export `JAVA_HOME` before running `./gradlew`).
- Commit after every task with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

Build command used throughout: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && ./gradlew <task> --console=plain`

---

### Task 1: Project scaffold that builds an empty mod

**Files:**
- Create: `settings.gradle`, `gradle.properties`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, `.gitignore`
- Create: `src/main/resources/fabric.mod.json`, `src/main/resources/hcheart.mixins.json`
- Create: `src/main/java/com/fracturedhardcore/hcheart/HcHeart.java`, `src/main/java/com/fracturedhardcore/hcheart/HcHeartMod.java`
- Create: `src/gametest/resources/fabric.mod.json`

**Interfaces:**
- Produces: `HcHeart.MOD_ID = "hcheart"`, `HcHeart.LOGGER`, `HcHeart.id(String) -> Identifier`.

- [ ] **Step 1: Copy the wrapper from the official 26.2 example mod**

```bash
B=https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2
mkdir -p gradle/wrapper
curl -sL -o gradlew $B/gradlew && curl -sL -o gradlew.bat $B/gradlew.bat
curl -sL -o gradle/wrapper/gradle-wrapper.jar $B/gradle/wrapper/gradle-wrapper.jar
curl -sL -o gradle/wrapper/gradle-wrapper.properties $B/gradle/wrapper/gradle-wrapper.properties
chmod +x gradlew
```

- [ ] **Step 2: Write the Gradle files**

`settings.gradle`:
```groovy
pluginManagement {
	repositories {
		maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
		mavenCentral()
		gradlePluginPortal()
	}
}
rootProject.name = 'fractured-hardcore'
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.parallel=true
org.gradle.configuration-cache=false

minecraft_version=26.2
loader_version=0.19.5
loom_version=1.17-SNAPSHOT
fabric_api_version=0.159.0+26.2

mod_version=0.1.0
maven_group=com.fracturedhardcore
archives_base_name=fractured-hardcore
```

`build.gradle`:
```groovy
plugins {
	id 'net.fabricmc.fabric-loom' version "${loom_version}"
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

loom {
	mods {
		"hcheart" { sourceSet sourceSets.main }
	}
}

fabricApi {
	configureTests {
		createSourceSet = true
		modId = "hcheart-gametest"
		enableGameTests = true
		enableClientGameTests = false
		eula = true
	}
}

dependencies {
	minecraft "com.mojang:minecraft:${project.minecraft_version}"
	implementation "net.fabricmc:fabric-loader:${project.loader_version}"
	implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

	testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}"
	testImplementation "org.junit.jupiter:junit-jupiter:5.12.2"
	testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

test {
	useJUnitPlatform()
	testLogging { events "passed", "failed", "skipped"; exceptionFormat "full" }
}

processResources {
	inputs.property "version", project.version
	filesMatching("fabric.mod.json") { expand "version": project.version }
}

tasks.withType(JavaCompile).configureEach { it.options.release = 25 }

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

jar {
	from("LICENSE") { rename { "${it}_${project.base.archivesName.get()}" } }
}
```

`.gitignore`:
```
.gradle/
build/
run/
out/
.idea/
*.iml
.vscode/
.DS_Store
```

- [ ] **Step 3: Write fabric.mod.json, mixins config, entrypoint**

`src/main/resources/fabric.mod.json`:
```json
{
	"schemaVersion": 1,
	"id": "hcheart",
	"version": "${version}",
	"name": "Fractured Hardcore",
	"description": "Hardcore with a safety net: get downed instead of dying, lose two hearts per true death, and craft Crimson Hearts to claw them back. Server-side only.",
	"authors": ["Musa Misto"],
	"license": "MIT",
	"environment": "*",
	"entrypoints": {
		"main": ["com.fracturedhardcore.hcheart.HcHeartMod"]
	},
	"mixins": ["hcheart.mixins.json"],
	"depends": {
		"fabricloader": ">=0.19.5",
		"minecraft": "~26.2",
		"java": ">=25",
		"fabric-api": "*"
	}
}
```

`src/main/resources/hcheart.mixins.json` (mixin class names are added by later tasks):
```json
{
	"required": true,
	"package": "com.fracturedhardcore.hcheart.mixin",
	"compatibilityLevel": "JAVA_25",
	"mixins": [],
	"injectors": { "defaultRequire": 1 }
}
```

`src/main/java/com/fracturedhardcore/hcheart/HcHeart.java`:
```java
package com.fracturedhardcore.hcheart;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HcHeart {
	public static final String MOD_ID = "hcheart";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private HcHeart() {}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
```

`src/main/java/com/fracturedhardcore/hcheart/HcHeartMod.java` (grows in later tasks):
```java
package com.fracturedhardcore.hcheart;

import net.fabricmc.api.ModInitializer;

public final class HcHeartMod implements ModInitializer {
	@Override
	public void onInitialize() {
		HcHeart.LOGGER.info("Fractured Hardcore loaded");
	}
}
```

`src/gametest/resources/fabric.mod.json`:
```json
{
	"schemaVersion": 1,
	"id": "hcheart-gametest",
	"version": "1.0.0",
	"name": "Fractured Hardcore GameTests",
	"environment": "*",
	"entrypoints": { "fabric-gametest": [] },
	"depends": { "hcheart": "*" }
}
```

- [ ] **Step 4: Build**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && ./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`, `build/libs/fractured-hardcore-0.1.0.jar` exists, `runGameTest` ran (0 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "build: scaffold Fabric 26.2 mod with gametest source set"
```

---

### Task 2: Pure core rules (unit-tested, no Minecraft imports)

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/core/Rules.java`, `PlayerRecord.java`, `DeathRules.java`, `ReviveRules.java`
- Test: `src/test/java/com/fracturedhardcore/hcheart/core/PlayerRecordTest.java`, `DeathRulesTest.java`, `ReviveRulesTest.java`

**Interfaces:**
- Produces: `PlayerRecord(int deaths, int restoresUsed, long downedUntilTick, String lastKnownName)` with `maxHearts()`, `maxHealth()`, `finalLife()`, `eliminated()`, `restoreCost()`, `isDowned()`, `downedExpired(long)`, `downedTicksRemaining(long)`, `canRestore()`, `withDeath()`, `withRestore()`, `withDownedUntil(long)`, `withDownedCleared()`, `withName(String)`, `withCounters(int,int)`; `PlayerRecord.FRESH`.
- `DeathRules.onLethalDamage(PlayerRecord, boolean bypass) -> Outcome{ENTER_DOWNED, TRUE_DEATH}`, `blocksDamageWhileDowned(PlayerRecord, boolean)`, `respawnsAsSpectator(PlayerRecord)`, `showsHardcoreUi(PlayerRecord)`, `shouldRescueFromSpectator(PlayerRecord, boolean isSpectator)`.
- `ReviveRules.DRAIN_TICKS`, `drainsAt(int)`, `canStart(int food)`, `isComplete(int)`, `progressPercent(int)`, `check(...) -> BreakReason`, `Hunger(int food, float saturation).drainOne()`.

- [ ] **Step 1: Write failing tests**

`PlayerRecordTest.java`:
```java
package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlayerRecordTest {
	@Test void ladderIs10_8_6_4_thenFloor() {
		assertEquals(10, rec(0).maxHearts());
		assertEquals(8, rec(1).maxHearts());
		assertEquals(6, rec(2).maxHearts());
		assertEquals(4, rec(3).maxHearts());
		assertEquals(4, rec(4).maxHearts());
		assertEquals(4, rec(99).maxHearts());
		assertEquals(8.0, rec(3).maxHealth());
	}
	@Test void finalLifeAtThreeEliminatedAtFour() {
		assertFalse(rec(2).finalLife()); assertTrue(rec(3).finalLife()); assertTrue(rec(4).finalLife());
		assertFalse(rec(3).eliminated()); assertTrue(rec(4).eliminated());
	}
	@Test void restoreCostEscalatesAndNeverResets() {
		PlayerRecord r = new PlayerRecord(3, 0, 0, "a");
		assertEquals(1, r.restoreCost());
		r = r.withRestore(); assertEquals(2, r.deaths()); assertEquals(2, r.restoreCost());
		r = r.withRestore(); assertEquals(1, r.deaths()); assertEquals(3, r.restoreCost());
		r = r.withDeath().withDeath(); assertEquals(3, r.deaths()); assertEquals(3, r.restoreCost());
	}
	@Test void restoreAtZeroDeathsIsRejected() {
		assertFalse(rec(0).canRestore());
		assertThrows(IllegalStateException.class, () -> rec(0).withRestore());
	}
	@Test void heartLiftsFinalLife() { assertFalse(rec(3).withRestore().finalLife()); }
	@Test void downedTimerAgainstAbsoluteTicks() {
		PlayerRecord r = rec(0).withDownedUntil(1000);
		assertTrue(r.isDowned()); assertFalse(r.downedExpired(999)); assertTrue(r.downedExpired(1000));
		assertEquals(400, r.downedTicksRemaining(600)); assertEquals(0, r.downedTicksRemaining(5000));
		assertFalse(r.withDownedCleared().isDowned());
		assertFalse(PlayerRecord.FRESH.downedExpired(Long.MAX_VALUE));
	}
	@Test void negativeValuesRejected() {
		assertThrows(IllegalArgumentException.class, () -> new PlayerRecord(-1, 0, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new PlayerRecord(0, -1, 0, ""));
		assertThrows(IllegalArgumentException.class, () -> new PlayerRecord(0, 0, -1, ""));
		assertEquals("", new PlayerRecord(0, 0, 0, null).lastKnownName());
	}
	private static PlayerRecord rec(int deaths) { return new PlayerRecord(deaths, 0, 0, "p"); }
}
```

`DeathRulesTest.java`:
```java
package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fracturedhardcore.hcheart.core.DeathRules.Outcome;

class DeathRulesTest {
	@Test void healthyPlayerGetsDowned() { assertEquals(Outcome.ENTER_DOWNED, DeathRules.onLethalDamage(rec(0, 0), false)); assertEquals(Outcome.ENTER_DOWNED, DeathRules.onLethalDamage(rec(2, 0), false)); }
	@Test void bypassDamageIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 0), true)); }
	@Test void finalLifeIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(3, 0), false)); }
	@Test void alreadyDownedIsTrueDeath() { assertEquals(Outcome.TRUE_DEATH, DeathRules.onLethalDamage(rec(0, 500), false)); }
	@Test void downedBlocksOnlyNonBypassDamage() {
		assertTrue(DeathRules.blocksDamageWhileDowned(rec(0, 500), false));
		assertFalse(DeathRules.blocksDamageWhileDowned(rec(0, 500), true));
		assertFalse(DeathRules.blocksDamageWhileDowned(rec(0, 0), false));
	}
	@Test void spectatorOnlyWhenEliminated() { assertFalse(DeathRules.respawnsAsSpectator(rec(3, 0))); assertTrue(DeathRules.respawnsAsSpectator(rec(4, 0))); }
	@Test void hardcoreUiOnFinalLife() { assertFalse(DeathRules.showsHardcoreUi(rec(2, 0))); assertTrue(DeathRules.showsHardcoreUi(rec(3, 0))); }
	@Test void rescueStrandedSpectatorsUnlessEliminated() {
		assertTrue(DeathRules.shouldRescueFromSpectator(rec(0, 0), true));
		assertTrue(DeathRules.shouldRescueFromSpectator(rec(3, 0), true));
		assertFalse(DeathRules.shouldRescueFromSpectator(rec(4, 0), true));
		assertFalse(DeathRules.shouldRescueFromSpectator(rec(0, 0), false));
	}
	private static PlayerRecord rec(int deaths, long downedUntil) { return new PlayerRecord(deaths, 0, downedUntil, "p"); }
}
```

`ReviveRulesTest.java`:
```java
package com.fracturedhardcore.hcheart.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.fracturedhardcore.hcheart.core.ReviveRules.BreakReason;
import com.fracturedhardcore.hcheart.core.ReviveRules.Hunger;

class ReviveRulesTest {
	@Test void drainsExactlySixPointsSpreadOverChannel() {
		assertArrayEquals(new int[] {27, 53, 80, 107, 133, 160}, ReviveRules.DRAIN_TICKS);
		int drains = 0;
		for (int t = 1; t <= Rules.REVIVE_DURATION_TICKS; t++) if (ReviveRules.drainsAt(t)) drains++;
		assertEquals(6, drains);
		assertFalse(ReviveRules.drainsAt(0)); assertFalse(ReviveRules.drainsAt(161));
	}
	@Test void saturationDrainsBeforeFood() {
		Hunger h = new Hunger(20, 2.5f);
		h = h.drainOne(); assertEquals(20, h.food()); assertEquals(1.5f, h.saturation());
		h = h.drainOne(); assertEquals(20, h.food()); assertEquals(0.5f, h.saturation());
		h = h.drainOne(); assertEquals(20, h.food()); assertEquals(0f, h.saturation());
		h = h.drainOne(); assertEquals(19, h.food()); assertEquals(0f, h.saturation());
		assertEquals(0, new Hunger(0, 0f).drainOne().food());
	}
	@Test void entryRequiresSixFood() { assertFalse(ReviveRules.canStart(5)); assertTrue(ReviveRules.canStart(6)); }
	@Test void completionAndProgress() {
		assertFalse(ReviveRules.isComplete(159)); assertTrue(ReviveRules.isComplete(160));
		assertEquals(0, ReviveRules.progressPercent(0)); assertEquals(50, ReviveRules.progressPercent(80)); assertEquals(100, ReviveRules.progressPercent(160));
	}
	@Test void breakConditions() {
		assertEquals(BreakReason.NONE, check(1.0, 4.0, false, false, 10, 10, true, true));
		assertEquals(BreakReason.REVIVER_MOVED, check(2.1 * 2.1, 4.0, false, false, 10, 10, true, true));
		assertEquals(BreakReason.NONE, check(2.0 * 2.0, 4.0, false, false, 10, 10, true, true));
		assertEquals(BreakReason.TOO_FAR_APART, check(0, 4.1 * 4.1, false, false, 10, 10, true, true));
		assertEquals(BreakReason.REVIVER_HURT, check(0, 0, true, false, 10, 10, true, true));
		assertEquals(BreakReason.TARGET_HURT, check(0, 0, false, true, 10, 10, true, true));
		assertEquals(BreakReason.REVIVER_STARVING, check(0, 0, false, false, 0, 10, true, true));
		assertEquals(BreakReason.TARGET_STARVING, check(0, 0, false, false, 10, 0, true, true));
		assertEquals(BreakReason.TARGET_NOT_DOWNED, check(0, 0, false, false, 10, 10, false, true));
		assertEquals(BreakReason.REVIVER_UNAVAILABLE, check(0, 0, false, false, 10, 10, true, false));
	}
	private static BreakReason check(double driftSq, double sepSq, boolean rHurt, boolean tHurt, int rFood, int tFood, boolean downed, boolean avail) {
		return ReviveRules.check(driftSq, sepSq, rHurt, tHurt, rFood, tFood, downed, avail);
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && ./gradlew test --console=plain`
Expected: compilation FAILS (classes missing).

- [ ] **Step 3: Implement the core**

`Rules.java`:
```java
package com.fracturedhardcore.hcheart.core;

/** Tunables. Pure constants; no Minecraft imports. */
public final class Rules {
	public static final int BASE_HEARTS = 10;
	public static final int FLOOR_HEARTS = 4;
	public static final int HEARTS_LOST_PER_DEATH = 2;
	public static final int FINAL_LIFE_DEATHS = 3;
	public static final int ELIMINATION_DEATHS = 4;
	public static final long DOWNED_DURATION_TICKS = 180L * 20L;
	public static final int REVIVE_DURATION_TICKS = 8 * 20;
	public static final int REVIVE_MIN_FOOD = 6;
	public static final int REVIVE_FOOD_COST = 6;
	public static final double REVIVE_MAX_REVIVER_DRIFT = 2.0;
	public static final double REVIVE_MAX_SEPARATION = 4.0;
	public static final double DOWNED_SPEED_MULTIPLIER = -0.5;
	public static final double DOWNED_JUMP_MULTIPLIER = -1.0;
	public static final int HEART_USE_COOLDOWN_TICKS = 20;
	private Rules() {}
}
```

`PlayerRecord.java`:
```java
package com.fracturedhardcore.hcheart.core;

/** Persistent per-player state. Immutable; every field is derived from these four values. */
public record PlayerRecord(int deaths, int restoresUsed, long downedUntilTick, String lastKnownName) {
	public static final PlayerRecord FRESH = new PlayerRecord(0, 0, 0L, "");

	public PlayerRecord {
		if (deaths < 0) throw new IllegalArgumentException("deaths must be >= 0");
		if (restoresUsed < 0) throw new IllegalArgumentException("restoresUsed must be >= 0");
		if (downedUntilTick < 0) throw new IllegalArgumentException("downedUntilTick must be >= 0");
		if (lastKnownName == null) lastKnownName = "";
	}

	public int maxHearts() { return Math.max(Rules.FLOOR_HEARTS, Rules.BASE_HEARTS - Rules.HEARTS_LOST_PER_DEATH * deaths); }
	public double maxHealth() { return maxHearts() * 2.0; }
	public boolean finalLife() { return deaths >= Rules.FINAL_LIFE_DEATHS; }
	public boolean eliminated() { return deaths >= Rules.ELIMINATION_DEATHS; }
	public int restoreCost() { return restoresUsed + 1; }
	public boolean canRestore() { return deaths > 0; }
	public boolean isDowned() { return downedUntilTick > 0; }
	public boolean downedExpired(long nowTick) { return isDowned() && nowTick >= downedUntilTick; }
	public long downedTicksRemaining(long nowTick) { return isDowned() ? Math.max(0L, downedUntilTick - nowTick) : 0L; }

	public PlayerRecord withDeath() { return new PlayerRecord(deaths + 1, restoresUsed, downedUntilTick, lastKnownName); }
	public PlayerRecord withRestore() {
		if (!canRestore()) throw new IllegalStateException("cannot restore at 0 deaths");
		return new PlayerRecord(deaths - 1, restoresUsed + 1, downedUntilTick, lastKnownName);
	}
	public PlayerRecord withDownedUntil(long tick) { return new PlayerRecord(deaths, restoresUsed, tick, lastKnownName); }
	public PlayerRecord withDownedCleared() { return withDownedUntil(0L); }
	public PlayerRecord withName(String name) { return new PlayerRecord(deaths, restoresUsed, downedUntilTick, name); }
	public PlayerRecord withCounters(int newDeaths, int newRestores) { return new PlayerRecord(newDeaths, newRestores, downedUntilTick, lastKnownName); }
}
```

`DeathRules.java`:
```java
package com.fracturedhardcore.hcheart.core;

/** Every death-path decision, as pure functions of the record. */
public final class DeathRules {
	public enum Outcome { ENTER_DOWNED, TRUE_DEATH }
	private DeathRules() {}

	/** Called when a player's health would drop to zero. */
	public static Outcome onLethalDamage(PlayerRecord rec, boolean bypassesInvulnerability) {
		if (bypassesInvulnerability) return Outcome.TRUE_DEATH;   // void, /kill, bleed-out
		if (rec.isDowned()) return Outcome.TRUE_DEATH;             // only bypass damage reaches here anyway
		if (rec.finalLife()) return Outcome.TRUE_DEATH;
		return Outcome.ENTER_DOWNED;
	}
	public static boolean blocksDamageWhileDowned(PlayerRecord rec, boolean bypassesInvulnerability) { return rec.isDowned() && !bypassesInvulnerability; }
	public static boolean respawnsAsSpectator(PlayerRecord rec) { return rec.eliminated(); }
	public static boolean showsHardcoreUi(PlayerRecord rec) { return rec.finalLife(); }
	public static boolean shouldRescueFromSpectator(PlayerRecord rec, boolean isSpectator) { return isSpectator && !rec.eliminated(); }
}
```

`ReviveRules.java`:
```java
package com.fracturedhardcore.hcheart.core;

/** Revive channel arithmetic and break conditions. */
public final class ReviveRules {
	public enum BreakReason { NONE, REVIVER_MOVED, TOO_FAR_APART, REVIVER_HURT, TARGET_HURT, REVIVER_STARVING, TARGET_STARVING, TARGET_NOT_DOWNED, REVIVER_UNAVAILABLE, PLAYER_LEFT }
	public record Hunger(int food, float saturation) {
		public Hunger drainOne() {
			if (saturation > 0f) return new Hunger(food, Math.max(0f, saturation - 1f));
			return new Hunger(Math.max(0, food - 1), 0f);
		}
	}
	/** 1-based elapsed ticks at which one point is drained from each participant: 27, 53, 80, 107, 133, 160. */
	public static final int[] DRAIN_TICKS = computeDrainTicks();
	private ReviveRules() {}

	private static int[] computeDrainTicks() {
		int[] ticks = new int[Rules.REVIVE_FOOD_COST];
		for (int i = 1; i <= Rules.REVIVE_FOOD_COST; i++) ticks[i - 1] = Math.round(i * (float) Rules.REVIVE_DURATION_TICKS / Rules.REVIVE_FOOD_COST);
		return ticks;
	}
	public static boolean drainsAt(int elapsedTicks) { for (int t : DRAIN_TICKS) if (t == elapsedTicks) return true; return false; }
	public static boolean canStart(int reviverFoodLevel) { return reviverFoodLevel >= Rules.REVIVE_MIN_FOOD; }
	public static boolean isComplete(int elapsedTicks) { return elapsedTicks >= Rules.REVIVE_DURATION_TICKS; }
	public static int progressPercent(int elapsedTicks) { return (int) Math.min(100L, Math.floorDiv(elapsedTicks * 100L, Rules.REVIVE_DURATION_TICKS)); }

	public static BreakReason check(double reviverDriftSq, double separationSq, boolean reviverHurt, boolean targetHurt,
			int reviverFood, int targetFood, boolean targetDowned, boolean reviverAvailable) {
		if (!reviverAvailable) return BreakReason.REVIVER_UNAVAILABLE;
		if (!targetDowned) return BreakReason.TARGET_NOT_DOWNED;
		if (reviverHurt) return BreakReason.REVIVER_HURT;
		if (targetHurt) return BreakReason.TARGET_HURT;
		if (reviverFood <= 0) return BreakReason.REVIVER_STARVING;
		if (targetFood <= 0) return BreakReason.TARGET_STARVING;
		if (reviverDriftSq > Rules.REVIVE_MAX_REVIVER_DRIFT * Rules.REVIVE_MAX_REVIVER_DRIFT) return BreakReason.REVIVER_MOVED;
		if (separationSq > Rules.REVIVE_MAX_SEPARATION * Rules.REVIVE_MAX_SEPARATION) return BreakReason.TOO_FAR_APART;
		return BreakReason.NONE;
	}
}
```

- [ ] **Step 4: Run tests**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && ./gradlew test --console=plain`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(core): pure state model and death/revive rules with unit tests"
```

---

### Task 3: Persistent state, audit log, scoreboard and the single mutation service

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/state/HeartCodecs.java`, `HeartState.java`, `AuditLog.java`, `HeartStateService.java`
- Create: `src/main/java/com/fracturedhardcore/hcheart/scoreboard/ScoreboardService.java`
- Test: `src/test/java/com/fracturedhardcore/hcheart/state/HeartCodecsTest.java`

**Interfaces:**
- Consumes: `PlayerRecord` (Task 2).
- Produces: `HeartState.TYPE : SavedDataType<HeartState>`, `HeartState.get(UUID)`, `put(UUID, PlayerRecord)`, `clear()`, `view()`.
  `HeartStateService.load(MinecraftServer)`; instance methods `get(UUID)`, `getOrCreate(UUID, String name)`, `now()`, `recordDeath(UUID)`, `restore(UUID)`, `enterDowned(UUID, long until)`, `clearDowned(UUID, String reason)`, `set(UUID, int deaths, int restores, String actor)`, `resetAll(String actor)`, `all()`, `flush()`, `audit()`, `scoreboard()`.
  `ScoreboardService(MinecraftServer)`: `ensureObjective()`, `sync(PlayerRecord)`, `clear(Collection<String> names)`.

- [ ] **Step 1: Write the failing codec test**

`HeartCodecsTest.java` (uses NbtOps; no registry bootstrap needed):
```java
package com.fracturedhardcore.hcheart.state;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class HeartCodecsTest {
	@Test void playersMapRoundTrips() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		Map<UUID, PlayerRecord> in = Map.of(a, new PlayerRecord(2, 1, 12345L, "skillux"), b, PlayerRecord.FRESH);
		Tag encoded = HeartCodecs.PLAYERS.encodeStart(NbtOps.INSTANCE, in).getOrThrow();
		Map<UUID, PlayerRecord> out = HeartCodecs.PLAYERS.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertEquals(in, out);
	}
	@Test void missingFieldsUseDefaults() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("deaths", 3);
		PlayerRecord rec = HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, tag).getOrThrow();
		assertEquals(new PlayerRecord(3, 0, 0L, ""), rec);
	}
	@Test void negativeDeathsFailToParseRatherThanCorruptState() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("deaths", -2);
		assertTrue(HeartCodecs.PLAYER_RECORD.parse(NbtOps.INSTANCE, tag).isError());
	}
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test` → compilation error (HeartCodecs missing).

- [ ] **Step 3: Implement**

`HeartCodecs.java`:
```java
package com.fracturedhardcore.hcheart.state;

import java.util.Map;
import java.util.UUID;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

public final class HeartCodecs {
	private HeartCodecs() {}

	public static final Codec<PlayerRecord> PLAYER_RECORD = RecordCodecBuilder.create(i -> i.group(
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("deaths", 0).forGetter(PlayerRecord::deaths),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("restores_used", 0).forGetter(PlayerRecord::restoresUsed),
			Codec.LONG.optionalFieldOf("downed_until", 0L).forGetter(PlayerRecord::downedUntilTick),
			Codec.STRING.optionalFieldOf("name", "").forGetter(PlayerRecord::lastKnownName)
	).apply(i, PlayerRecord::new));

	public static final Codec<Map<UUID, PlayerRecord>> PLAYERS = Codec.unboundedMap(UUIDUtil.STRING_CODEC, PLAYER_RECORD);
}
```

`HeartState.java`:
```java
package com.fracturedhardcore.hcheart.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** World-level store: UUID -> PlayerRecord. Lives in <world>/data. */
public final class HeartState extends SavedData {
	public static final Codec<HeartState> CODEC = RecordCodecBuilder.create(i -> i.group(
			HeartCodecs.PLAYERS.optionalFieldOf("players", Map.of()).forGetter(s -> Map.copyOf(s.players))
	).apply(i, HeartState::new));

	// SAVED_DATA_COMMAND_STORAGE is registered as an opaque (DSL::remainder) type in every vanilla schema,
	// so no data fixer will ever rewrite our fields. It is the type vanilla uses for arbitrary /data storage NBT.
	public static final SavedDataType<HeartState> TYPE = new SavedDataType<>(HcHeart.id("players"), HeartState::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<UUID, PlayerRecord> players = new HashMap<>();

	public HeartState() {}
	private HeartState(Map<UUID, PlayerRecord> players) { this.players.putAll(players); }

	public PlayerRecord get(UUID id) { return players.getOrDefault(id, PlayerRecord.FRESH); }
	public boolean contains(UUID id) { return players.containsKey(id); }
	public void put(UUID id, PlayerRecord rec) { players.put(id, rec); setDirty(); }
	public void clear() { players.clear(); setDirty(); }
	public Map<UUID, PlayerRecord> view() { return Collections.unmodifiableMap(players); }
}
```

`AuditLog.java`:
```java
package com.fracturedhardcore.hcheart.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import com.fracturedhardcore.hcheart.HcHeart;

/** Append-only text log of every state change: timestamp, uuid, name, event, details. */
public final class AuditLog {
	private final Path file;

	public AuditLog(Path file) { this.file = file; }

	public synchronized void log(UUID id, String name, String event, String details) {
		String line = Instant.now() + " " + id + " " + (name == null || name.isEmpty() ? "?" : name) + " " + event + " " + details + System.lineSeparator();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			HcHeart.LOGGER.error("Could not write audit log {}", file, e);
		}
		HcHeart.LOGGER.info("[audit] {} ({}) {} {}", name, id, event, details);
	}
}
```

`ScoreboardService.java`:
```java
package com.fracturedhardcore.hcheart.scoreboard;

import java.util.Collection;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Mirrors currentDeaths into the `deaths_hc` objective shown in the tab list. */
public final class ScoreboardService {
	public static final String OBJECTIVE = "deaths_hc";
	private final MinecraftServer server;

	public ScoreboardService(MinecraftServer server) { this.server = server; }

	public Objective ensureObjective() {
		ServerScoreboard sb = server.getScoreboard();
		Objective objective = sb.getObjective(OBJECTIVE);
		if (objective == null) {
			objective = sb.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal("Deaths"), ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		if (sb.getDisplayObjective(DisplaySlot.LIST) == null) sb.setDisplayObjective(DisplaySlot.LIST, objective);
		return objective;
	}

	public void sync(PlayerRecord rec) {
		if (rec.lastKnownName().isEmpty()) return;
		server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(rec.lastKnownName()), ensureObjective()).set(rec.deaths());
	}

	public void clear(Collection<String> names) {
		Objective objective = ensureObjective();
		for (String name : names) if (!name.isEmpty()) server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective).set(0);
	}
}
```

`HeartStateService.java`:
```java
package com.fracturedhardcore.hcheart.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.scoreboard.ScoreboardService;
import net.minecraft.server.MinecraftServer;

/**
 * The ONLY code path that mutates player records. Every mutation is persisted and flushed to disk
 * synchronously, written to the audit log, and mirrored to the scoreboard before returning.
 */
public final class HeartStateService {
	private final MinecraftServer server;
	private final HeartState state;
	private final AuditLog audit;
	private final ScoreboardService scoreboard;

	public HeartStateService(MinecraftServer server, HeartState state, AuditLog audit, ScoreboardService scoreboard) {
		this.server = server; this.state = state; this.audit = audit; this.scoreboard = scoreboard;
	}

	public static HeartStateService load(MinecraftServer server) {
		HeartState state = server.getDataStorage().computeIfAbsent(HeartState.TYPE);
		AuditLog audit = new AuditLog(server.getServerDirectory().resolve("logs").resolve("hcheart-audit.log"));
		HcHeart.LOGGER.info("Loaded Fractured Hardcore state for {} player(s)", state.view().size());
		return new HeartStateService(server, state, audit, new ScoreboardService(server));
	}

	public PlayerRecord get(UUID id) { return state.get(id); }
	public Map<UUID, PlayerRecord> all() { return state.view(); }
	public AuditLog audit() { return audit; }
	public ScoreboardService scoreboard() { return scoreboard; }
	public long now() { return server.overworld().getGameTime(); }

	/** First-join init (0 deaths, 0 restores) and name refresh. */
	public PlayerRecord getOrCreate(UUID id, String name) {
		PlayerRecord rec = state.get(id);
		if (!state.contains(id)) return commit(id, rec.withName(name), "INIT", "first join");
		if (!rec.lastKnownName().equals(name)) return commit(id, rec.withName(name), "RENAME", "was '" + rec.lastKnownName() + "'");
		return rec;
	}
	public PlayerRecord recordDeath(UUID id) {
		PlayerRecord rec = state.get(id).withDeath().withDownedCleared();
		return commit(id, rec, "DEATH", "deaths=" + rec.deaths() + " hearts=" + rec.maxHearts() + (rec.eliminated() ? " ELIMINATED" : rec.finalLife() ? " FINAL_LIFE" : ""));
	}
	public PlayerRecord restore(UUID id) {
		PlayerRecord before = state.get(id);
		PlayerRecord rec = before.withRestore();
		return commit(id, rec, "RESTORE", "cost=" + before.restoreCost() + " deaths=" + rec.deaths() + " hearts=" + rec.maxHearts() + " nextCost=" + rec.restoreCost());
	}
	public PlayerRecord enterDowned(UUID id, long untilTick) { return commit(id, state.get(id).withDownedUntil(untilTick), "DOWNED", "until=" + untilTick); }
	public PlayerRecord clearDowned(UUID id, String reason) { return commit(id, state.get(id).withDownedCleared(), "DOWNED_CLEARED", reason); }
	public PlayerRecord set(UUID id, int deaths, int restores, String actor) {
		return commit(id, state.get(id).withCounters(deaths, restores).withDownedCleared(), "SET", "deaths=" + deaths + " restores=" + restores + " by " + actor);
	}
	public void resetAll(String actor) {
		List<String> names = new ArrayList<>();
		state.view().forEach((id, rec) -> { names.add(rec.lastKnownName()); audit.log(id, rec.lastKnownName(), "RESET", "was deaths=" + rec.deaths() + " restores=" + rec.restoresUsed() + " by " + actor); });
		state.clear();
		flush();
		scoreboard.clear(names);
	}

	private PlayerRecord commit(UUID id, PlayerRecord rec, String event, String details) {
		state.put(id, rec);
		flush();
		audit.log(id, rec.lastKnownName(), event, details);
		scoreboard.sync(rec);
		return rec;
	}

	/** Synchronous write of all dirty saved data. Cheap: only called on rare state changes. */
	public void flush() {
		try {
			server.getDataStorage().saveAndJoin();
		} catch (RuntimeException e) {
			HcHeart.LOGGER.error("Failed to flush Fractured Hardcore state", e);
		}
	}
}
```

- [ ] **Step 4: Run tests** — `./gradlew test` → PASS (3 new tests).

- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(state): SavedData store, audit log, scoreboard mirror and mutation service"`

---

### Task 4: HealthService, join handler, mod wiring, gametest harness

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/health/HealthService.java`, `join/JoinHandler.java`, `join/RespawnService.java`, `Services.java`
- Modify: `src/main/java/com/fracturedhardcore/hcheart/HcHeartMod.java`
- Create: `src/gametest/java/com/fracturedhardcore/hcheart/gametest/TestPlayers.java`, `Hc.java`, `JoinGameTests.java`
- Modify: `src/gametest/resources/fabric.mod.json` (register `JoinGameTests`)

**Interfaces:**
- Produces: `HealthService.PENALTY_ID`, `HealthService.normalize(ServerPlayer, PlayerRecord)`, `HealthService.refill(ServerPlayer)`.
  `RespawnService.rescueFromSpectator(ServerPlayer) -> ServerPlayer`.
  `JoinHandler.onJoin(ServerPlayer)`.
  `Services(MinecraftServer server, HeartStateService state, DownedManager downed, ReviveManager revive)`; `HcHeartMod.services()` (nullable), `HcHeartMod.state()`.
  Gametest helpers: `TestPlayers.join(GameTestHelper, String name, Vec3 relativePos) -> ServerPlayer`, `TestPlayers.leave(ServerPlayer)`, `Hc.state()`, `Hc.downed()`, `Hc.revive()`.
- Note: `DownedManager` and `ReviveManager` are created in Tasks 5–6; in this task create them as the real classes with only the methods the join handler needs (`clearPresentation`, `reenter`, `bleedOut`, `onViewerJoined`, `onPlayerLeft`, `cancelFor`) — see Task 5/6 code, which fills in the bodies. To keep this task compilable, Task 4 creates `downed/DownedManager.java` and `downed/ReviveManager.java` with the full Task 5/6 code already (they are self-contained) — executors: copy those files from Tasks 5 and 6 now; their gametests come later.

- [ ] **Step 1: Write the gametest harness and failing join tests**

`Hc.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import java.util.Objects;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.downed.DownedManager;
import com.fracturedhardcore.hcheart.downed.ReviveManager;
import com.fracturedhardcore.hcheart.state.HeartStateService;

final class Hc {
	private Hc() {}
	static Services services() { return Objects.requireNonNull(HcHeartMod.services(), "mod services not initialised"); }
	static HeartStateService state() { return services().state(); }
	static DownedManager downed() { return services().downed(); }
	static ReviveManager revive() { return services().revive(); }
}
```

`TestPlayers.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real ServerPlayers with a dead-end connection, placed through PlayerList.placeNewPlayer so the mod's JOIN handler runs. */
final class TestPlayers {
	private TestPlayers() {}

	static ServerPlayer join(GameTestHelper helper, String name, Vec3 relativePos) {
		MinecraftServer server = helper.getLevel().getServer();
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation()) {
			@Override public boolean isClientAuthoritative() { return false; }
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		Vec3 abs = helper.absoluteVec(relativePos);
		player.teleportTo(helper.getLevel(), abs.x, abs.y, abs.z, Set.of(), 0f, 0f, false);
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		return player;
	}

	static void leave(ServerPlayer player) {
		if (player.getServer() != null && player.getServer().getPlayerList().getPlayer(player.getUUID()) != null) {
			player.getServer().getPlayerList().remove(player);
		}
	}
}
```

`JoinGameTests.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.health.HealthService;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class JoinGameTests {
	@GameTest
	public void joinResetsBaseValueAndAppliesPenalty(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "skillux", new Vec3(4, 2, 4));
		try {
			p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(18.0);            // the /attribute damage from the brief
			Hc.state().set(p.getUUID(), 2, 0, "test");
			JoinHandler.onJoin(p);                                                // simulate a rejoin
			helper.assertValueEqual(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 20.0, "base value");
			helper.assertValueEqual((double) p.getMaxHealth(), 12.0, "max health at 2 deaths");
			helper.assertTrue(p.getHealth() <= 12.0f, "health clamped to the new cap");
			helper.assertTrue(p.getAttribute(Attributes.MAX_HEALTH).hasModifier(HealthService.PENALTY_ID), "penalty modifier present");
			Hc.state().set(p.getUUID(), 0, 0, "test");
			JoinHandler.onJoin(p);
			helper.assertValueEqual((double) p.getMaxHealth(), 20.0, "max health at 0 deaths");
			helper.assertFalse(p.getAttribute(Attributes.MAX_HEALTH).hasModifier(HealthService.PENALTY_ID), "no modifier at 0 deaths");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void joinRescuesStrandedSpectator(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "stranded", new Vec3(4, 2, 4));
		try {
			p.setGameMode(GameType.SPECTATOR);
			p.setHealth(3.0f);
			JoinHandler.onJoin(p);
			helper.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "game mode");
			helper.assertValueEqual(p.getHealth(), p.getMaxHealth(), "health refilled");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void joinLeavesEliminatedInSpectator(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "eliminated", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 4, 0, "test");
			p.setGameMode(GameType.SPECTATOR);
			JoinHandler.onJoin(p);
			helper.assertValueEqual(p.gameMode(), GameType.SPECTATOR, "stays spectator");
			helper.assertValueEqual((double) p.getMaxHealth(), 8.0, "floor of 4 hearts");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}
}
```

Add to `src/gametest/resources/fabric.mod.json` → `"fabric-gametest": ["com.fracturedhardcore.hcheart.gametest.JoinGameTests"]`.

- [ ] **Step 2: Run** `./gradlew build` → compilation error (missing classes).

- [ ] **Step 3: Implement**

`HealthService.java`:
```java
package com.fracturedhardcore.hcheart.health;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Owns the max-health attribute: base is always 20, the penalty is one transient modifier recomputed from state. */
public final class HealthService {
	public static final Identifier PENALTY_ID = HcHeart.id("heart_penalty");
	private HealthService() {}

	public static void normalize(ServerPlayer player, PlayerRecord rec) {
		AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
		if (attr == null) return;
		attr.setBaseValue(20.0);
		attr.removeModifier(PENALTY_ID);
		double delta = rec.maxHealth() - 20.0;
		if (delta != 0.0) attr.addTransientModifier(new AttributeModifier(PENALTY_ID, delta, AttributeModifier.Operation.ADD_VALUE));
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
	}

	public static void refill(ServerPlayer player) { player.setHealth(player.getMaxHealth()); }
}
```

`RespawnService.java`:
```java
package com.fracturedhardcore.hcheart.join;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;

public final class RespawnService {
	private RespawnService() {}

	/** Order matters: game mode, teleport, health, effects. Returns the (possibly re-created) player. */
	public static ServerPlayer rescueFromSpectator(ServerPlayer player) {
		player.setGameMode(GameType.SURVIVAL);
		TeleportTransition transition = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
		ServerPlayer moved = player.teleport(transition);
		ServerPlayer target = moved != null ? moved : player;
		target.setHealth(target.getMaxHealth());
		target.removeAllEffects();
		target.getFoodData().setFoodLevel(20);
		target.getFoodData().setSaturation(5f);
		return target;
	}
}
```

`JoinHandler.java`:
```java
package com.fracturedhardcore.hcheart.join;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.health.HealthService;
import net.minecraft.server.level.ServerPlayer;

/** Runs on every join. Everything else in the mod assumes this has run. */
public final class JoinHandler {
	private JoinHandler() {}

	public static void onJoin(ServerPlayer player) {
		Services s = HcHeartMod.services();
		if (s == null) return;
		PlayerRecord rec = s.state().getOrCreate(player.getUUID(), player.getGameProfile().name());

		// 1+2. Own the base value and reapply the penalty purely from stored state.
		HealthService.normalize(player, rec);

		// 3. Rescue anyone stranded in spectator by stock hardcore rules (unless their run is over).
		if (DeathRules.shouldRescueFromSpectator(rec, player.isSpectator())) {
			player = RespawnService.rescueFromSpectator(player);
		}

		// 4. Resolve downed state against world time; clear stale presentation otherwise.
		if (rec.isDowned()) {
			if (rec.downedExpired(s.state().now())) s.downed().bleedOut(player);
			else s.downed().reenter(player, rec);
		} else {
			s.downed().clearPresentation(player);
		}
		s.downed().onViewerJoined(player);

		// 5. Scoreboard.
		s.state().scoreboard().sync(rec);
	}
}
```

`Services.java`:
```java
package com.fracturedhardcore.hcheart;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import com.fracturedhardcore.hcheart.downed.ReviveManager;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.server.MinecraftServer;

/** Per-server runtime objects. Created when the server starts, dropped when it stops. */
public record Services(MinecraftServer server, HeartStateService state, DownedManager downed, ReviveManager revive) {
	public static Services create(MinecraftServer server) {
		HeartStateService state = HeartStateService.load(server);
		DownedManager downed = new DownedManager(server, state);
		ReviveManager revive = new ReviveManager(server, state, downed);
		downed.attachRevive(revive);
		return new Services(server, state, downed, revive);
	}
}
```

`HcHeartMod.java` (full wiring; the `register` calls for Tasks 5–9 are added by those tasks):
```java
package com.fracturedhardcore.hcheart;

import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.jspecify.annotations.Nullable;

public final class HcHeartMod implements ModInitializer {
	private static @Nullable Services services;

	public static @Nullable Services services() { return services; }

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> services = Services.create(server));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> { if (services != null) services.state().scoreboard().ensureObjective(); });
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (services != null) services.state().flush(); });
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> services = null);

		ServerPlayerEvents.JOIN.register(JoinHandler::onJoin);
		ServerPlayerEvents.LEAVE.register(player -> {
			if (services == null) return;
			services.revive().cancelFor(player.getUUID());
			services.downed().onPlayerLeft(player);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (services == null) return;
			services.downed().tick();
			services.revive().tick();
		});
		HcHeart.LOGGER.info("Fractured Hardcore loaded");
	}
}
```

- [ ] **Step 4: Run** `./gradlew build` → BUILD SUCCESSFUL, gametest log shows 3 tests passed (`build/run/gameTest/logs/latest.log` and the JUnit XML under `build/test-results/` or `build/run/gameTest`).

- [ ] **Step 5: Commit** — `git commit -am "feat: health normalisation, join handler and gametest harness"` (add new files first).

---

### Task 5: Downed state — manager, events, mixins, gametests

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/downed/DownedManager.java`, `downed/DownedEvents.java`, `downed/Text.java`
- Create: `src/main/java/com/fracturedhardcore/hcheart/mixin/PlayerMixin.java`, `mixin/WardenMixin.java`
- Modify: `src/main/resources/hcheart.mixins.json` (add `PlayerMixin`, `WardenMixin`), `HcHeartMod.java` (call `DownedEvents.register()`)
- Create: `src/gametest/java/com/fracturedhardcore/hcheart/gametest/DownedGameTests.java`; register in gametest `fabric.mod.json`

**Interfaces:**
- Produces: `DownedManager(MinecraftServer, HeartStateService)`; `attachRevive(ReviveManager)`; `isDowned(ServerPlayer)`; `static isDownedPlayer(Player)`; `enter(ServerPlayer)`; `reenter(ServerPlayer, PlayerRecord)`; `clear(ServerPlayer, String reason)`; `clearPresentation(ServerPlayer)`; `bleedOut(ServerPlayer)`; `tick()`; `onViewerJoined(ServerPlayer)`; `onPlayerLeft(ServerPlayer)`; `Identifier SPEED_ID, JUMP_ID`.
  `Text.mmss(long ticks)`, `Text.info(String) -> MutableComponent` (gray), `Text.warn(String)` (red), `Text.good(String)` (green).

- [ ] **Step 1: Write failing gametests**

`DownedGameTests.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class DownedGameTests {
	static void lethal(ServerPlayer p) { p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f); }

	@GameTest
	public void lethalDamageEntersDownedInsteadOfDeath(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed1", new Vec3(4, 2, 4));
		try {
			lethal(p);
			helper.assertTrue(p.isAlive() && !p.isDeadOrDying(), "player survives");
			helper.assertValueEqual(p.getHealth(), 1.0f, "health pinned at 1");
			helper.assertTrue(Hc.state().get(p.getUUID()).isDowned(), "downed flag persisted");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).downedUntilTick(), Hc.state().now() + 3600L, "180 s timer against world time");
			helper.assertTrue(p.hasGlowingTag(), "glowing");
			helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "prone");
			helper.assertTrue(p.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(DownedManager.SPEED_ID), "slowed");
			helper.assertTrue(p.getAttribute(Attributes.JUMP_STRENGTH).hasModifier(DownedManager.JUMP_ID), "no jump");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "no death counted");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void downedPlayerIsImmuneToOrdinaryDamage(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed2", new Vec3(4, 2, 4));
		try {
			lethal(p);
			boolean hurt = p.hurtServer(p.level(), p.level().damageSources().generic(), 5f);
			helper.assertFalse(hurt, "damage rejected");
			helper.assertValueEqual(p.getHealth(), 1.0f, "still 1 HP");
			helper.assertTrue(p.isAlive(), "alive");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void bypassDamageKillsDownedPlayerForReal(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed3", new Vec3(4, 2, 4));
		try {
			lethal(p);
			p.hurtServer(p.level(), p.level().damageSources().genericKill(), Float.MAX_VALUE);
			helper.assertTrue(p.isDeadOrDying(), "dead");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
			helper.assertFalse(Hc.state().get(p.getUUID()).isDowned(), "downed cleared");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void mobsDropAndCannotReacquireDownedTarget(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed4", new Vec3(4, 2, 4));
		Zombie zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 2, 1));
		zombie.setTarget(p);
		helper.assertTrue(zombie.getTarget() == p, "zombie targets a healthy player");
		helper.assertTrue(zombie.canAttack(p), "canAttack before");
		lethal(p);
		helper.runAfterDelay(2, () -> {
			try {
				helper.assertTrue(zombie.getTarget() == null, "target cleared by sweep");
				helper.assertFalse(zombie.canAttack(p), "cannot re-target a downed player");
				helper.assertFalse(TargetingConditions.forCombat().test(helper.getLevel(), zombie, p), "TargetingConditions rejects");
				zombie.setTarget(p);
				helper.assertTrue(zombie.getTarget() == null, "setTarget filtered out");
			} finally { TestPlayers.leave(p); }
			helper.succeed();
		});
	}

	@GameTest
	public void wardenCannotTargetDownedPlayer(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed5", new Vec3(4, 2, 4));
		try {
			Warden warden = helper.spawnWithNoFreeWill(EntityTypes.WARDEN, new BlockPos(1, 2, 1));
			helper.assertTrue(warden.canTargetEntity(p), "warden can target a healthy survival player");
			lethal(p);
			helper.assertFalse(warden.canTargetEntity(p), "warden ignores downed player");
			helper.assertFalse(warden.canAttack(p), "warden canAttack false");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void bleedOutKillsAndCountsDeath(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed6", new Vec3(4, 2, 4));
		lethal(p);
		Hc.state().enterDowned(p.getUUID(), Hc.state().now() + 20);     // shorten the 180 s clock for the test
		helper.runAfterDelay(30, () -> {
			try {
				helper.assertTrue(p.isDeadOrDying(), "bled out");
				helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
				helper.assertFalse(Hc.state().get(p.getUUID()).isDowned(), "downed cleared");
				helper.assertFalse(p.hasGlowingTag(), "glow removed");
			} finally { TestPlayers.leave(p); }
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void proneHitboxDoesNotSuffocateInOneBlockGap(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed7", new Vec3(4.5, 1, 4.5));
		helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);   // ceiling directly above the feet block: a 1-block gap
		lethal(p);
		helper.runAfterDelay(40, () -> {
			try {
				helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "still prone");
				helper.assertFalse(p.isInWall(), "eyes not inside a block");
				helper.assertTrue(p.isAlive(), "alive");
				helper.assertValueEqual(p.getHealth(), 1.0f, "no suffocation damage");
			} finally { TestPlayers.leave(p); }
			helper.succeed();
		});
	}

	@GameTest
	public void reloginResolvesDownedState(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "downed8", new Vec3(4, 2, 4));
		try {
			lethal(p);
			Hc.downed().clearPresentation(p);                                // simulate a crash: state kept, presentation lost
			helper.assertFalse(p.hasGlowingTag(), "presentation gone");
			com.fracturedhardcore.hcheart.join.JoinHandler.onJoin(p);      // rejoin with time remaining
			helper.assertTrue(p.hasGlowingTag(), "re-entered downed");
			helper.assertValueEqual(p.getPose(), Pose.SWIMMING, "prone again");
			Hc.state().enterDowned(p.getUUID(), 1L);                        // rejoin after the clock expired
			com.fracturedhardcore.hcheart.join.JoinHandler.onJoin(p);
			helper.assertTrue(p.isDeadOrDying(), "bled out on join");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}
}
```

- [ ] **Step 2: Run** `./gradlew build` → compilation error.

- [ ] **Step 3: Implement**

`Text.java`:
```java
package com.fracturedhardcore.hcheart.downed;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class Text {
	private Text() {}
	public static String mmss(long ticks) { long s = Math.max(0, ticks) / 20; return String.format("%d:%02d", s / 60, s % 60); }
	public static MutableComponent info(String s) { return Component.literal(s).withStyle(ChatFormatting.GRAY); }
	public static MutableComponent warn(String s) { return Component.literal(s).withStyle(ChatFormatting.RED); }
	public static MutableComponent good(String s) { return Component.literal(s).withStyle(ChatFormatting.GREEN); }
	public static MutableComponent gold(String s) { return Component.literal(s).withStyle(ChatFormatting.GOLD); }
}
```

`DownedManager.java`:
```java
package com.fracturedhardcore.hcheart.downed;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Entry, per-tick enforcement, bleed-out, teardown and crash recovery of the downed state. */
public final class DownedManager {
	public static final Identifier SPEED_ID = HcHeart.id("downed_speed");
	public static final Identifier JUMP_ID = HcHeart.id("downed_jump");
	private static final double SWEEP_RADIUS = 48.0;

	private final MinecraftServer server;
	private final HeartStateService state;
	private ReviveManager revive;
	private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

	public DownedManager(MinecraftServer server, HeartStateService state) { this.server = server; this.state = state; }
	public void attachRevive(ReviveManager revive) { this.revive = revive; }

	public boolean isDowned(ServerPlayer player) { return state.get(player.getUUID()).isDowned(); }

	/** Mixin entry point: cheap map lookup, safe before the server has started. */
	public static boolean isDownedPlayer(Player player) {
		Services s = HcHeartMod.services();
		return s != null && player instanceof ServerPlayer sp && s.downed().isDowned(sp);
	}

	public void enter(ServerPlayer player) {
		long until = state.now() + Rules.DOWNED_DURATION_TICKS;
		PlayerRecord rec = state.enterDowned(player.getUUID(), until);          // persist FIRST
		player.setHealth(1.0f);
		player.clearFire();
		player.stopUsingItem();
		applyPresentation(player, rec);
		sweepTargets(player);
		String where = player.level().dimension().identifier().getPath() + " at " + player.blockPosition().getX() + ", " + player.blockPosition().getY() + ", " + player.blockPosition().getZ();
		server.getPlayerList().broadcastSystemMessage(Text.warn(player.getGameProfile().name() + " is downed in " + where + " — " + Text.mmss(Rules.DOWNED_DURATION_TICKS) + " to revive them."), false);
		player.sendSystemMessage(Text.gold("You are downed. Crawl to safety — a friend can right-click you to revive you."), false);
	}

	/** Re-apply presentation from persisted state (join after crash/relog). */
	public void reenter(ServerPlayer player, PlayerRecord rec) {
		player.setHealth(1.0f);
		applyPresentation(player, rec);
		sweepTargets(player);
	}

	/** Full teardown: state + presentation + any revive channel. Idempotent. */
	public void clear(ServerPlayer player, String reason) {
		if (state.get(player.getUUID()).isDowned()) state.clearDowned(player.getUUID(), reason);
		clearPresentation(player);
		if (revive != null) revive.cancelTarget(player.getUUID());
	}

	/** Presentation only (glow, pose, modifiers, boss bar). Used on join when not downed to clear crash leftovers. */
	public void clearPresentation(ServerPlayer player) {
		player.setGlowingTag(false);
		removeModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID);
		removeModifier(player, Attributes.JUMP_STRENGTH, JUMP_ID);
		if (player.getPose() == Pose.SWIMMING) player.setPose(Pose.STANDING);
		ServerBossEvent bar = bars.remove(player.getUUID());
		if (bar != null) bar.removeAllPlayers();
	}

	/** The only downed→dead route besides bypass damage. generic_kill bypasses invulnerability, totems and armour. */
	public void bleedOut(ServerPlayer player) {
		player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
		if (!player.isDeadOrDying()) {
			HcHeart.LOGGER.error("Bleed-out kill did not take for {}; clearing downed state", player.getGameProfile().name());
			clear(player, "bleed-out fallback");
		}
	}

	public void tick() {
		long now = state.now();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerRecord rec = state.get(player.getUUID());
			if (!rec.isDowned() || player.isDeadOrDying()) continue;
			if (rec.downedExpired(now)) { bleedOut(player); continue; }
			if (player.getHealth() != 1.0f) player.setHealth(1.0f);
			if (!player.hasGlowingTag()) player.setGlowingTag(true);
			if (player.getPose() != Pose.SWIMMING) player.setPose(Pose.SWIMMING);
			if (now % 20 == 0) {
				sweepTargets(player);
				ServerBossEvent bar = bars.get(player.getUUID());
				if (bar == null) applyPresentation(player, rec); else updateBar(player, rec, bar, now);
			}
		}
	}

	public void onViewerJoined(ServerPlayer viewer) { bars.values().forEach(bar -> bar.addPlayer(viewer)); }
	public void onPlayerLeft(ServerPlayer player) { bars.values().forEach(bar -> bar.removePlayer(player)); }

	private void applyPresentation(ServerPlayer player, PlayerRecord rec) {
		player.setGlowingTag(true);
		applyModifier(player, Attributes.MOVEMENT_SPEED, SPEED_ID, Rules.DOWNED_SPEED_MULTIPLIER);
		applyModifier(player, Attributes.JUMP_STRENGTH, JUMP_ID, Rules.DOWNED_JUMP_MULTIPLIER);
		player.setPose(Pose.SWIMMING);
		ServerBossEvent bar = bars.computeIfAbsent(player.getUUID(), id -> new ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
		updateBar(player, rec, bar, state.now());
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) bar.addPlayer(viewer);
	}

	private void updateBar(ServerPlayer player, PlayerRecord rec, ServerBossEvent bar, long now) {
		long remaining = rec.downedTicksRemaining(now);
		bar.setName(Component.literal("☠ " + player.getGameProfile().name() + " is downed · " + Text.mmss(remaining) + " · right-click to revive"));
		bar.setProgress((float) remaining / (float) Rules.DOWNED_DURATION_TICKS);
	}

	private void sweepTargets(ServerPlayer player) {
		AABB box = player.getBoundingBox().inflate(SWEEP_RADIUS);
		for (Mob mob : player.level().getEntitiesOfClass(Mob.class, box, m -> m.getTarget() == player)) mob.setTarget(null);
		for (Warden warden : player.level().getEntitiesOfClass(Warden.class, box)) warden.clearAnger(player);
	}

	private static void applyModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
		AttributeInstance attr = player.getAttribute(attribute);
		if (attr == null) return;
		attr.removeModifier(id);
		attr.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}
	private static void removeModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id) {
		AttributeInstance attr = player.getAttribute(attribute);
		if (attr != null) attr.removeModifier(id);
	}
}
```

`DownedEvents.java`:
```java
package com.fracturedhardcore.hcheart.downed;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

public final class DownedEvents {
	private DownedEvents() {}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			Services s = HcHeartMod.services();
			if (s == null || !(entity instanceof ServerPlayer player)) return true;
			PlayerRecord rec = s.state().get(player.getUUID());
			boolean bypass = source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
			return switch (DeathRules.onLethalDamage(rec, bypass)) {
				case TRUE_DEATH -> true;
				case ENTER_DOWNED -> { s.downed().enter(player); yield false; }   // enter() sets health to 1
			};
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			Services s = HcHeartMod.services();
			if (s == null || !(entity instanceof ServerPlayer player)) return true;
			return !DeathRules.blocksDamageWhileDowned(s.state().get(player.getUUID()), source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
		});
		// Interaction lock. Registered before HeartItem/Revive handlers so a downed actor is refused first.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> lock(player));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> lock(player));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> lock(player));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> lock(player));
		UseItemCallback.EVENT.register((player, level, hand) -> lock(player));
	}

	private static InteractionResult lock(Player player) {
		if (player instanceof ServerPlayer sp && DownedManager.isDownedPlayer(sp)) {
			sp.sendSystemMessage(Text.warn("You are downed and cannot do that."), true);
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}
}
```

`PlayerMixin.java`:
```java
package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** All hostile targeting funnels through canAttack(target) -> target.canBeSeenAsEnemy(). */
	@Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
	private void hcheart$untargetableWhileDowned(CallbackInfoReturnable<Boolean> cir) {
		if (DownedManager.isDownedPlayer((Player) (Object) this)) cir.setReturnValue(false);
	}

	/** Vanilla recomputes the pose every tick; keep the server pose prone so the hitbox stays 0.6 blocks tall. */
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void hcheart$proneWhileDowned(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (DownedManager.isDownedPlayer(self)) {
			if (self.getPose() != Pose.SWIMMING) self.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
```

`WardenMixin.java`:
```java
package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.downed.DownedManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Warden targets through anger/vibrations and its own canTargetEntity, not the standard target goals. */
@Mixin(Warden.class)
public abstract class WardenMixin {
	@Inject(method = "canTargetEntity", at = @At("HEAD"), cancellable = true)
	private void hcheart$ignoreDownedPlayers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof Player player && DownedManager.isDownedPlayer(player)) cir.setReturnValue(false);
	}
}
```

Add `"PlayerMixin", "WardenMixin"` to `hcheart.mixins.json`; add `DownedEvents.register();` in `HcHeartMod.onInitialize()` before the tick registration; add `DownedGameTests` to the gametest `fabric.mod.json`.

- [ ] **Step 4: Run** `./gradlew build` → all gametests pass.

- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(downed): downed state with immunity, de-targeting, prone pose, bleed-out and recovery"`

---

### Task 6: Revive channel

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/downed/ReviveManager.java`, `downed/ReviveEvents.java`
- Modify: `HcHeartMod.java` (call `ReviveEvents.register()` after `DownedEvents.register()`)
- Create: `src/gametest/java/com/fracturedhardcore/hcheart/gametest/ReviveGameTests.java`; register in gametest `fabric.mod.json`

**Interfaces:**
- Consumes: `DownedManager.clear/isDowned`, `HeartStateService`, `ReviveRules`, `Text`.
- Produces: `ReviveManager(MinecraftServer, HeartStateService, DownedManager)`; `tryStart(ServerPlayer reviver, ServerPlayer target) -> InteractionResult`; `isChanneling(UUID target)`; `tick()`; `cancelFor(UUID anyParticipant)`; `cancelTarget(UUID target)`.

- [ ] **Step 1: Write failing gametests**

`ReviveGameTests.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

public class ReviveGameTests {
	private static ServerPlayer downedTarget(GameTestHelper helper, String name) {
		ServerPlayer t = TestPlayers.join(helper, name, new Vec3(4, 2, 4));
		t.getFoodData().setFoodLevel(17);          // < 18 so natural regeneration does not touch saturation during the test
		t.getFoodData().setSaturation(0f);
		DownedGameTests.lethal(t);
		return t;
	}
	private static ServerPlayer reviver(GameTestHelper helper, String name) {
		ServerPlayer r = TestPlayers.join(helper, name, new Vec3(5, 2, 4));
		r.getFoodData().setFoodLevel(20);
		r.getFoodData().setSaturation(5f);
		return r;
	}

	@GameTest(maxTicks = 220)
	public void reviveSucceedsWithNoPenalty(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t1");
		ServerPlayer r = reviver(helper, "rv_r1");
		helper.assertTrue(Hc.revive().tryStart(r, t).consumesAction(), "channel started");
		helper.runAfterDelay(170, () -> {
			try {
				helper.assertFalse(Hc.state().get(t.getUUID()).isDowned(), "target no longer downed");
				helper.assertValueEqual(Hc.state().get(t.getUUID()).deaths(), 0, "no death counted");
				helper.assertValueEqual(t.getHealth(), t.getMaxHealth(), "health restored");
				helper.assertFalse(t.hasGlowingTag(), "glow cleared");
				helper.assertTrue(t.getPose() != Pose.SWIMMING, "pose cleared");
				helper.assertValueEqual(r.getFoodData().getSaturationLevel(), 0f, "reviver saturation drained first (5 points)");
				helper.assertValueEqual(r.getFoodData().getFoodLevel(), 19, "reviver food drained by the sixth point");
				helper.assertValueEqual(t.getFoodData().getFoodLevel(), 11, "target lost 6 food points");
				helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel gone");
			} finally { TestPlayers.leave(t); TestPlayers.leave(r); }
			helper.succeed();
		});
	}

	@GameTest
	public void reviveRefusedWhenReviverIsHungry(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t2");
		ServerPlayer r = reviver(helper, "rv_r2");
		try {
			r.getFoodData().setFoodLevel(5);
			helper.assertValueEqual(Hc.revive().tryStart(r, t), InteractionResult.FAIL, "refused");
			helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "no channel");
		} finally { TestPlayers.leave(t); TestPlayers.leave(r); }
		helper.succeed();
	}

	@GameTest
	public void onlyOneReviverPerTarget(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t3");
		ServerPlayer r1 = reviver(helper, "rv_r3a");
		ServerPlayer r2 = reviver(helper, "rv_r3b");
		try {
			helper.assertTrue(Hc.revive().tryStart(r1, t).consumesAction(), "first reviver starts");
			helper.assertValueEqual(Hc.revive().tryStart(r2, t), InteractionResult.FAIL, "second reviver refused");
			helper.assertTrue(Hc.revive().tryStart(r1, t).consumesAction(), "first reviver re-click is harmless");
		} finally { TestPlayers.leave(t); TestPlayers.leave(r1); TestPlayers.leave(r2); }
		helper.succeed();
	}

	@GameTest(maxTicks = 80)
	public void reviveBreaksWhenReviverMoves(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t4");
		ServerPlayer r = reviver(helper, "rv_r4");
		Hc.revive().tryStart(r, t);
		helper.runAfterDelay(10, () -> {
			Vec3 away = helper.absoluteVec(new Vec3(1, 2, 1));
			r.teleportTo(helper.getLevel(), away.x, away.y, away.z, Set.of(), 0f, 0f, false);
		});
		helper.runAfterDelay(15, () -> {
			try {
				helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broken");
				helper.assertTrue(Hc.state().get(t.getUUID()).isDowned(), "target still downed");
			} finally { TestPlayers.leave(t); TestPlayers.leave(r); }
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void reviveBreaksWhenReviverIsHurt(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t5");
		ServerPlayer r = reviver(helper, "rv_r5");
		Hc.revive().tryStart(r, t);
		helper.runAfterDelay(10, () -> r.hurtServer(r.level(), r.level().damageSources().generic(), 1f));
		helper.runAfterDelay(15, () -> {
			try { helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broken by damage"); }
			finally { TestPlayers.leave(t); TestPlayers.leave(r); }
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void reviveBreaksWhenReviverStarves(GameTestHelper helper) {
		ServerPlayer t = downedTarget(helper, "rv_t6");
		ServerPlayer r = reviver(helper, "rv_r6");
		Hc.revive().tryStart(r, t);
		helper.runAfterDelay(10, () -> r.getFoodData().setFoodLevel(0));
		helper.runAfterDelay(15, () -> {
			try { helper.assertFalse(Hc.revive().isChanneling(t.getUUID()), "channel broken by hunger"); }
			finally { TestPlayers.leave(t); TestPlayers.leave(r); }
			helper.succeed();
		});
	}
}
```

- [ ] **Step 2: Run** `./gradlew build` → compilation error.

- [ ] **Step 3: Implement**

`ReviveManager.java`:
```java
package com.fracturedhardcore.hcheart.downed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fracturedhardcore.hcheart.core.ReviveRules;
import com.fracturedhardcore.hcheart.core.ReviveRules.BreakReason;
import com.fracturedhardcore.hcheart.core.ReviveRules.Hunger;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.state.HeartStateService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One 8-second channel per downed target; first reviver wins. Ticked from END_SERVER_TICK. */
public final class ReviveManager {
	private static final class Channel {
		final UUID reviver; final UUID target; final Vec3 start;
		int elapsed; int lastReviverHurtTime; int lastTargetHurtTime;
		Channel(ServerPlayer reviver, ServerPlayer target) {
			this.reviver = reviver.getUUID(); this.target = target.getUUID(); this.start = reviver.position();
			this.lastReviverHurtTime = reviver.hurtTime; this.lastTargetHurtTime = target.hurtTime;
		}
	}

	private final MinecraftServer server;
	private final HeartStateService state;
	private final DownedManager downed;
	private final Map<UUID, Channel> byTarget = new HashMap<>();

	public ReviveManager(MinecraftServer server, HeartStateService state, DownedManager downed) { this.server = server; this.state = state; this.downed = downed; }

	public boolean isChanneling(UUID target) { return byTarget.containsKey(target); }

	public InteractionResult tryStart(ServerPlayer reviver, ServerPlayer target) {
		if (reviver == target || !state.get(target.getUUID()).isDowned()) return InteractionResult.PASS;
		if (downed.isDowned(reviver) || reviver.isSpectator() || reviver.isDeadOrDying()) return InteractionResult.FAIL;
		Channel existing = byTarget.get(target.getUUID());
		if (existing != null) {
			if (existing.reviver.equals(reviver.getUUID())) return InteractionResult.SUCCESS;
			ServerPlayer other = server.getPlayerList().getPlayer(existing.reviver);
			reviver.sendSystemMessage(Text.warn(name(target) + " is already being revived by " + (other != null ? name(other) : "someone else") + "."), true);
			return InteractionResult.FAIL;
		}
		if (!ReviveRules.canStart(reviver.getFoodData().getFoodLevel())) {
			reviver.sendSystemMessage(Text.warn("You need at least " + Rules.REVIVE_MIN_FOOD + " food points (3 drumsticks) to revive someone."), true);
			return InteractionResult.FAIL;
		}
		byTarget.put(target.getUUID(), new Channel(reviver, target));
		reviver.sendSystemMessage(Text.good("Reviving " + name(target) + "… stay within 2 blocks."), true);
		target.sendSystemMessage(Text.good(name(reviver) + " is reviving you… hold still."), true);
		return InteractionResult.SUCCESS;
	}

	public void tick() {
		if (byTarget.isEmpty()) return;
		for (Channel ch : List.copyOf(byTarget.values())) {
			ServerPlayer reviver = server.getPlayerList().getPlayer(ch.reviver);
			ServerPlayer target = server.getPlayerList().getPlayer(ch.target);
			if (reviver == null || target == null) { end(ch, BreakReason.PLAYER_LEFT, reviver, target); continue; }
			boolean reviverHurt = reviver.hurtTime > ch.lastReviverHurtTime;
			boolean targetHurt = target.hurtTime > ch.lastTargetHurtTime;
			ch.lastReviverHurtTime = reviver.hurtTime; ch.lastTargetHurtTime = target.hurtTime;
			boolean reviverAvailable = reviver.isAlive() && !reviver.isSpectator() && !downed.isDowned(reviver) && reviver.level() == target.level();
			BreakReason reason = ReviveRules.check(reviver.position().distanceToSqr(ch.start), reviver.distanceToSqr(target), reviverHurt, targetHurt,
					reviver.getFoodData().getFoodLevel(), target.getFoodData().getFoodLevel(), state.get(ch.target).isDowned(), reviverAvailable);
			if (reason != BreakReason.NONE) { end(ch, reason, reviver, target); continue; }
			ch.elapsed++;
			if (ReviveRules.drainsAt(ch.elapsed)) { drainOne(reviver); drainOne(target); }
			if (ReviveRules.isComplete(ch.elapsed)) { succeed(ch, reviver, target); continue; }
			if (ch.elapsed % 4 == 0) {
				int pct = ReviveRules.progressPercent(ch.elapsed);
				reviver.sendSystemMessage(Text.good("Reviving " + name(target) + "… " + pct + "%"), true);
				target.sendSystemMessage(Text.good(name(reviver) + " is reviving you… " + pct + "%"), true);
			}
		}
	}

	/** Cancels any channel the player takes part in (used on disconnect). */
	public void cancelFor(UUID player) {
		for (Channel ch : List.copyOf(byTarget.values())) {
			if (ch.target.equals(player) || ch.reviver.equals(player)) end(ch, BreakReason.PLAYER_LEFT, server.getPlayerList().getPlayer(ch.reviver), server.getPlayerList().getPlayer(ch.target));
		}
	}
	/** Cancels the channel on a target that stopped being downed (death, admin reset). */
	public void cancelTarget(UUID target) {
		Channel ch = byTarget.remove(target);
		if (ch == null) return;
		ServerPlayer reviver = server.getPlayerList().getPlayer(ch.reviver);
		if (reviver != null) reviver.sendSystemMessage(Text.warn("Revive interrupted: they are no longer downed."), true);
	}

	private void succeed(Channel ch, ServerPlayer reviver, ServerPlayer target) {
		byTarget.remove(ch.target);
		downed.clear(target, "revived by " + name(reviver));
		target.setHealth(target.getMaxHealth());
		target.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.6f, 1.0f);
		server.getPlayerList().broadcastSystemMessage(Text.good(name(reviver) + " revived " + name(target) + "!"), false);
		state.audit().log(target.getUUID(), name(target), "REVIVED", "by " + name(reviver));
	}

	private void end(Channel ch, BreakReason reason, @Nullable ServerPlayer reviver, @Nullable ServerPlayer target) {
		byTarget.remove(ch.target);
		String why = switch (reason) {
			case REVIVER_MOVED -> "the reviver moved away.";
			case TOO_FAR_APART -> "you are too far apart.";
			case REVIVER_HURT -> "the reviver took damage.";
			case TARGET_HURT -> "the downed player took damage.";
			case REVIVER_STARVING -> "the reviver ran out of food.";
			case TARGET_STARVING -> "the downed player ran out of food.";
			case TARGET_NOT_DOWNED -> "they are no longer downed.";
			case REVIVER_UNAVAILABLE -> "the reviver can no longer help.";
			case PLAYER_LEFT -> "a player left.";
			case NONE -> "";
		};
		if (reviver != null) reviver.sendSystemMessage(Text.warn("Revive interrupted: " + why), true);
		if (target != null) target.sendSystemMessage(Text.warn("Revive interrupted: " + why), true);
	}

	private static void drainOne(ServerPlayer player) {
		FoodData food = player.getFoodData();
		Hunger after = new Hunger(food.getFoodLevel(), food.getSaturationLevel()).drainOne();
		food.setFoodLevel(after.food());
		food.setSaturation(after.saturation());
	}
	private static String name(ServerPlayer p) { return p.getGameProfile().name(); }
}
```

`ReviveEvents.java`:
```java
package com.fracturedhardcore.hcheart.downed;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

public final class ReviveEvents {
	private ReviveEvents() {}
	/** Right-clicking a downed player starts the channel. Registered after the downed interaction lock. */
	public static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			Services s = HcHeartMod.services();
			if (s == null || level.isClientSide() || !(player instanceof ServerPlayer reviver) || !(entity instanceof ServerPlayer target)) return InteractionResult.PASS;
			return s.revive().tryStart(reviver, target);
		});
	}
}
```

- [ ] **Step 4: Run** `./gradlew build` → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(revive): 8-second single-reviver channel with hunger drain and break conditions"`

---

### Task 7: True death, respawn, messaging, hardcore-UI mixins

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/death/DeathEvents.java`, `death/Messages.java`, `death/Sounds.java`
- Create: `src/main/java/com/fracturedhardcore/hcheart/mixin/ServerGamePacketListenerImplMixin.java`, `mixin/PlayerListMixin.java`
- Modify: `hcheart.mixins.json` (add both), `HcHeartMod.java` (call `DeathEvents.register()`)
- Create: `src/gametest/java/com/fracturedhardcore/hcheart/gametest/DeathGameTests.java`; register it

**Interfaces:**
- Produces: `Messages.selfDeathLines(PlayerRecord) -> List<Component>`, `othersDeathLine(String, PlayerRecord)`, `eliminated()`, `hearts(int)`, `restoreBroadcast(String, int cost, PlayerRecord after)`, `needHearts(int cost, int have)`, `alreadyFull()`; `Sounds.playTo(ServerPlayer, SoundEvent, SoundSource, float volume, float pitch)`.

- [ ] **Step 1: Write failing gametests**

`DeathGameTests.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class DeathGameTests {
	private static void clickRespawn(ServerPlayer p) {
		p.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
	}

	@GameTest
	public void trueDeathReducesHeartsOnRespawn(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death1", new Vec3(4, 2, 4));
		ServerPlayer end = p;
		try {
			p.hurtServer(p.level(), p.level().damageSources().genericKill(), Float.MAX_VALUE);
			helper.assertTrue(p.isDeadOrDying(), "dead");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "death counted once");
			end = helper.getLevel().getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
			helper.assertValueEqual((double) end.getMaxHealth(), 16.0, "8 hearts after first death");
			helper.assertValueEqual(end.getHealth(), 16.0f, "respawned at the new maximum");
			helper.assertValueEqual(end.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 20.0, "base value owned");
			helper.assertValueEqual(end.gameMode(), GameType.SURVIVAL, "survival");
		} finally { TestPlayers.leave(end); }
		helper.succeed();
	}

	@GameTest
	public void respawnButtonKeepsSurvivalUntilElimination(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death2", new Vec3(4, 2, 4));
		ServerPlayer end = p;
		try {
			Hc.state().set(p.getUUID(), 2, 0, "test");
			JoinHandler.onJoin(p);
			p.hurtServer(p.level(), p.level().damageSources().genericKill(), Float.MAX_VALUE);
			clickRespawn(p);
			end = p.connection.player;
			helper.assertTrue(end != p, "new player entity after respawn");
			helper.assertValueEqual(end.gameMode(), GameType.SURVIVAL, "third death still respawns");
			helper.assertValueEqual((double) end.getMaxHealth(), 8.0, "4 hearts on final life");
			helper.assertTrue(Hc.state().get(end.getUUID()).finalLife(), "final life flag");
		} finally { TestPlayers.leave(end); }
		helper.succeed();
	}

	@GameTest
	public void finalLifeDiesOutrightAndEliminationSpectates(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death3", new Vec3(4, 2, 4));
		ServerPlayer end = p;
		try {
			Hc.state().set(p.getUUID(), 3, 0, "test");
			JoinHandler.onJoin(p);
			p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f);     // ordinary damage: no downed safety net
			helper.assertTrue(p.isDeadOrDying(), "final life has no safety net");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 4, "eliminated");
			clickRespawn(p);
			end = p.connection.player;
			helper.assertValueEqual(end.gameMode(), GameType.SPECTATOR, "stock hardcore spectator behaviour after elimination");
		} finally { TestPlayers.leave(end); }
		helper.succeed();
	}

	@GameTest
	public void totemIsFinalLifeInsurance(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death4", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 3, 0, "test");
			JoinHandler.onJoin(p);
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f);
			helper.assertTrue(p.isAlive() && !p.isDeadOrDying(), "totem saved the player");
			helper.assertTrue(p.getOffhandItem().isEmpty(), "totem consumed");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 3, "no death counted for a totem save");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void totemIsNotConsumedBeforeFinalLife(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "death5", new Vec3(4, 2, 4));
		try {
			p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
			p.hurtServer(p.level(), p.level().damageSources().generic(), 1000f);
			helper.assertTrue(Hc.state().get(p.getUUID()).isDowned(), "downed takes precedence");
			helper.assertTrue(p.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "totem kept");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}
}
```

- [ ] **Step 2: Run** → compilation error.

- [ ] **Step 3: Implement**

`Sounds.java`:
```java
package com.fracturedhardcore.hcheart.death;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class Sounds {
	private Sounds() {}
	/** Plays a sound to exactly one player, at their own position. */
	public static void playTo(ServerPlayer player, SoundEvent sound, SoundSource source, float volume, float pitch) {
		player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source,
				player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
	}
}
```

`Messages.java`:
```java
package com.fracturedhardcore.hcheart.death;

import java.util.ArrayList;
import java.util.List;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.downed.Text;
import net.minecraft.network.chat.Component;

public final class Messages {
	private Messages() {}

	public static String hearts(int n) { return n + (n == 1 ? " Heart" : " Hearts"); }

	/** Chat (not action bar) so it persists and can be scrolled back. */
	public static List<Component> selfDeathLines(PlayerRecord rec) {
		List<Component> lines = new ArrayList<>();
		lines.add(Text.warn("You died. Deaths: " + rec.deaths() + " · Max health reduced to " + rec.maxHearts() + " hearts."));
		lines.add(Text.info("Craft a Crimson Heart to restore a level. Next restoration costs " + hearts(rec.restoreCost()) + "."));
		if (rec.deaths() == Rules.FINAL_LIFE_DEATHS - 1) lines.add(Text.gold("One more death puts you on your final life."));
		else if (rec.finalLife()) lines.add(Text.warn("You are now on your final life. The next death is permanent."));
		return lines;
	}
	public static Component othersDeathLine(String name, PlayerRecord rec) {
		if (rec.eliminated()) return Text.warn(name + "'s run has ended.");
		if (rec.finalLife()) return Text.warn(name + " died and is now on their final life (4 hearts).");
		return Text.info(name + " died · deaths " + rec.deaths() + " · " + rec.maxHearts() + " hearts");
	}
	public static Component eliminated() { return Text.warn("Your run is over. You may spectate the world."); }
	public static Component restoreBroadcast(String name, int cost, PlayerRecord after) {
		return Text.gold(name + " consumed " + hearts(cost) + " and is back to " + after.maxHearts() + " hearts. Their next restoration costs " + hearts(after.restoreCost()) + ".");
	}
	public static Component needHearts(int cost, int have) { return Text.warn("You need " + hearts(cost) + " to restore a level (you have " + have + ")."); }
	public static Component alreadyFull() { return Text.warn("You are already at full health."); }
}
```

`DeathEvents.java`:
```java
package com.fracturedhardcore.hcheart.death;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.downed.Text;
import com.fracturedhardcore.hcheart.health.HealthService;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class DeathEvents {
	private DeathEvents() {}

	public static void register() {
		// Fires at ServerPlayer.die TAIL: the death is committed (a totem save never reaches here).
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Services s = HcHeartMod.services();
			if (s == null || !(entity instanceof ServerPlayer player)) return;
			s.downed().clear(player, "died");
			PlayerRecord rec = s.state().recordDeath(player.getUUID());
			Component line = Messages.othersDeathLine(player.getGameProfile().name(), rec);
			for (ServerPlayer other : s.server().getPlayerList().getPlayers()) {
				if (other == player) continue;
				other.sendSystemMessage(line, false);
				Sounds.playTo(other, SoundEvents.BELL_RESONATE, SoundSource.MASTER, 0.3f, 0.6f);
			}
		});
		// Fires at PlayerList.respawn TAIL with the new entity fully in the world. alive == true is an End-portal trip, not a death.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			Services s = HcHeartMod.services();
			if (s == null || alive) return;
			PlayerRecord rec = s.state().get(newPlayer.getUUID());
			HealthService.normalize(newPlayer, rec);
			HealthService.refill(newPlayer);
			if (rec.eliminated()) {
				newPlayer.connection.send(new ClientboundSetTitleTextPacket(Text.warn("Your run is over.")));
				newPlayer.connection.send(new ClientboundSetSubtitleTextPacket(Text.info("You may spectate the world.")));
				newPlayer.sendSystemMessage(Messages.eliminated(), false);
			} else {
				Sounds.playTo(newPlayer, SoundEvents.BELL_RESONATE, SoundSource.MASTER, 1.0f, 0.6f);
				for (Component line : Messages.selfDeathLines(rec)) newPlayer.sendSystemMessage(line, false);
			}
			s.state().scoreboard().sync(rec);
		});
	}
}
```

`ServerGamePacketListenerImplMixin.java`:
```java
package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla: after PERFORM_RESPAWN, `if (server.isHardcore()) player.setGameMode(SPECTATOR)`. We spectate only when eliminated. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow public ServerPlayer player;

	@WrapOperation(method = "handleClientCommand", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isHardcore()Z"))
	private boolean hcheart$spectateOnlyWhenEliminated(MinecraftServer server, Operation<Boolean> original) {
		Services s = HcHeartMod.services();
		if (s == null) return original.call(server);
		return DeathRules.respawnsAsSpectator(s.state().get(this.player.getUUID()));
	}
}
```

`PlayerListMixin.java`:
```java
package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.DeathRules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client shows "Respawn" vs "Spectate world" (and hardcore hearts) from the `hardcore` flag of the login packet,
 * which vanilla fills from levelData.isHardcore(). Send true only for players on their final life.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelData;isHardcore()Z"))
	private boolean hcheart$hardcoreUiOnlyOnFinalLife(LevelData levelData, Operation<Boolean> original, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
		Services s = HcHeartMod.services();
		if (s == null) return original.call(levelData);
		return DeathRules.showsHardcoreUi(s.state().getOrCreate(player.getUUID(), player.getGameProfile().name()));
	}
}
```

Add both mixins to `hcheart.mixins.json`; add `DeathEvents.register();` in `HcHeartMod`; register `DeathGameTests`.

- [ ] **Step 4: Run** `./gradlew build` → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(death): death counting, respawn cap, messaging, respawn/hardcore-UI mixins"`

---

### Task 8: Crimson Heart — detection, consume handler, recipe datapack, sink guards

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/heart/HeartItem.java`
- Create: `src/main/java/com/fracturedhardcore/hcheart/mixin/BeaconPaymentSlotMixin.java`, `mixin/IngredientMixin.java`
- Create: `src/main/resources/data/hcheart/recipe/crimson_heart.json`, `data/hcheart/advancement/crafted_crimson_heart.json`, `data/hcheart/function/crafted.mcfunction`
- Modify: `hcheart.mixins.json`, `HcHeartMod.java` (call `HeartItem.register()` after `ReviveEvents.register()`)
- Create: `src/gametest/java/com/fracturedhardcore/hcheart/gametest/HeartGameTests.java`; register it

**Interfaces:**
- Produces: `HeartItem.isHeart(ItemStack)`, `HeartItem.create(int count) -> ItemStack`, `HeartItem.count(ServerPlayer)`, `HeartItem.remove(ServerPlayer, ItemStack heldFirst, int amount) -> int removed`, `HeartItem.register()`, `HeartItem.RECIPE_ID`.

- [ ] **Step 1: Write failing gametests**

`HeartGameTests.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import java.util.List;
import java.util.Optional;
import com.fracturedhardcore.hcheart.heart.HeartItem;
import com.fracturedhardcore.hcheart.join.JoinHandler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;

public class HeartGameTests {
	private static Optional<RecipeHolder<CraftingRecipe>> craft(GameTestHelper helper, CraftingInput input) {
		return helper.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
	}
	private static InteractionResult use(ServerPlayer p) { return p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND); }

	@GameTest
	public void recipeLoadsAndCraftsAHeart(GameTestHelper helper) {
		ItemStack e = new ItemStack(Items.ECHO_SHARD), n = new ItemStack(Items.NETHERITE_SCRAP), star = new ItemStack(Items.NETHER_STAR);
		CraftingInput input = CraftingInput.of(3, 3, List.of(e, n, e, n, star, n, e, n, e));
		Optional<RecipeHolder<CraftingRecipe>> recipe = craft(helper, input);
		helper.assertTrue(recipe.isPresent(), "crimson heart recipe matched");
		helper.assertValueEqual(recipe.get().id().identifier(), HeartItem.RECIPE_ID, "matched our recipe");
		ItemStack result = recipe.get().value().assemble(input, helper.getLevel().registryAccess());
		helper.assertTrue(HeartItem.isHeart(result), "result carries the hcheart tag");
		helper.assertValueEqual(result.getCount(), 1, "one heart");
		helper.succeed();
	}

	@GameTest
	public void heartIsNeverACraftingIngredient(GameTestHelper helper) {
		ItemStack heart = HeartItem.create(1), plain = new ItemStack(Items.NETHER_STAR), glass = new ItemStack(Items.GLASS), obsidian = new ItemStack(Items.OBSIDIAN);
		helper.assertFalse(Ingredient.of(Items.NETHER_STAR).test(heart), "Ingredient rejects a Heart");
		helper.assertTrue(Ingredient.of(Items.NETHER_STAR).test(plain), "Ingredient accepts a plain star");
		helper.assertFalse(craft(helper, CraftingInput.of(3, 3, List.of(glass, glass, glass, glass, heart, glass, obsidian, obsidian, obsidian))).isPresent(), "beacon recipe refuses a Heart");
		helper.assertTrue(craft(helper, CraftingInput.of(3, 3, List.of(glass, glass, glass, glass, plain, glass, obsidian, obsidian, obsidian))).isPresent(), "beacon recipe still works with a plain star");
		helper.succeed();
	}

	@GameTest
	public void heartCannotBeFedToABeacon(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_b", new Vec3(4, 2, 4));
		try {
			BeaconMenu menu = new BeaconMenu(0, p.getInventory());
			helper.assertFalse(menu.getSlot(0).mayPlace(HeartItem.create(1)), "payment slot rejects a Heart");
			helper.assertTrue(menu.getSlot(0).mayPlace(new ItemStack(Items.NETHER_STAR)), "payment slot accepts a plain star");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest(maxTicks = 80)
	public void consumingRestoresALevelWithEscalatingCost(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_c", new Vec3(4, 2, 4));
		Hc.state().set(p.getUUID(), 2, 0, "test");
		JoinHandler.onJoin(p);
		p.setItemInHand(InteractionHand.MAIN_HAND, HeartItem.create(3));
		helper.assertTrue(use(p).consumesAction(), "first use accepted (cost 1)");
		helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "deaths 2 -> 1");
		helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 1, "restores 1");
		helper.assertValueEqual(HeartItem.count(p), 2, "one heart consumed");
		helper.assertValueEqual((double) p.getMaxHealth(), 16.0, "8 hearts");
		helper.assertValueEqual(p.getHealth(), 16.0f, "refilled");
		helper.assertFalse(use(p).consumesAction(), "immediate re-use blocked by cooldown");
		helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "unchanged during cooldown");
		helper.runAfterDelay(25, () -> {
			try {
				helper.assertTrue(use(p).consumesAction(), "second use accepted (cost 2)");
				helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "deaths 1 -> 0");
				helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 2, "restores 2");
				helper.assertValueEqual(HeartItem.count(p), 0, "two more hearts consumed");
				helper.assertValueEqual((double) p.getMaxHealth(), 20.0, "back to 10 hearts");
			} finally { TestPlayers.leave(p); }
			helper.succeed();
		});
	}

	@GameTest
	public void consumingRefusedWhenShortOrFull(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_s", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 1, 2, "test");                     // cost is 3
			JoinHandler.onJoin(p);
			p.setItemInHand(InteractionHand.MAIN_HAND, HeartItem.create(2));
			helper.assertFalse(use(p).consumesAction(), "refused with 2 of 3 hearts");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 1, "unchanged");
			helper.assertValueEqual(HeartItem.count(p), 2, "nothing consumed");
			Hc.state().set(p.getUUID(), 0, 0, "test");
			JoinHandler.onJoin(p);
			helper.assertFalse(use(p).consumesAction(), "refused at full health");
			helper.assertValueEqual(HeartItem.count(p), 2, "nothing consumed");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void consumingLiftsFinalLifeAndDrawsFromAnySlot(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "heart_f", new Vec3(4, 2, 4));
		try {
			Hc.state().set(p.getUUID(), 3, 0, "test");
			JoinHandler.onJoin(p);
			p.setItemInHand(InteractionHand.MAIN_HAND, HeartItem.create(1));
			p.getInventory().setItem(20, HeartItem.create(1));               // a second heart elsewhere in the inventory
			Hc.state().set(p.getUUID(), 3, 1, "test");                     // cost 2
			helper.assertTrue(use(p).consumesAction(), "accepted");
			helper.assertFalse(Hc.state().get(p.getUUID()).finalLife(), "final life lifted");
			helper.assertValueEqual((double) p.getMaxHealth(), 12.0, "6 hearts");
			helper.assertValueEqual(HeartItem.count(p), 0, "both hearts consumed");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}
}
```

- [ ] **Step 2: Run** → compilation error.

- [ ] **Step 3: Implement**

`HeartItem.java`:
```java
package com.fracturedhardcore.hcheart.heart;

import com.fracturedhardcore.hcheart.HcHeart;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.core.Rules;
import com.fracturedhardcore.hcheart.death.Messages;
import com.fracturedhardcore.hcheart.health.HealthService;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;

/** The Crimson Heart is a Nether Star with custom_data {hcheart: true}. Nothing is registered. */
public final class HeartItem {
	public static final String TAG = "hcheart";
	public static final Identifier RECIPE_ID = HcHeart.id("crimson_heart");
	private HeartItem() {}

	public static boolean isHeart(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.NETHER_STAR)) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(TAG, false);
	}

	/** Same components as the recipe result. Used by /hc give and tests. */
	public static ItemStack create(int count) {
		ItemStack stack = new ItemStack(Items.NETHER_STAR, count);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(TAG, true));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Crimson Heart").withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)));
		stack.set(DataComponents.RARITY, Rarity.EPIC);
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	public static int count(ServerPlayer player) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) if (isHeart(inv.getItem(i))) n += inv.getItem(i).getCount();
		return n;
	}

	/** Removes `amount` hearts, held stack first, then any inventory slot. Returns how many were removed. */
	public static int remove(ServerPlayer player, ItemStack heldFirst, int amount) {
		int left = amount;
		if (isHeart(heldFirst)) { int take = Math.min(left, heldFirst.getCount()); heldFirst.shrink(take); left -= take; }
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!isHeart(s)) continue;
			int take = Math.min(left, s.getCount());
			s.shrink(take); left -= take;
		}
		return amount - left;
	}

	public static void register() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (!isHeart(stack)) return InteractionResult.PASS;
			if (level.isClientSide()) return InteractionResult.SUCCESS;   // never reached on a dedicated server; avoids client/server desync when installed on a client
			if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			return consume(sp, stack);
		});
	}

	static InteractionResult consume(ServerPlayer sp, ItemStack stack) {
		Services s = HcHeartMod.services();
		if (s == null) return InteractionResult.PASS;
		if (s.downed().isDowned(sp)) return InteractionResult.FAIL;
		if (sp.getCooldowns().isOnCooldown(stack)) return InteractionResult.FAIL;   // guards double-send use packets
		PlayerRecord rec = s.state().get(sp.getUUID());
		if (!rec.canRestore()) { sp.sendSystemMessage(Messages.alreadyFull(), true); return InteractionResult.FAIL; }
		int cost = rec.restoreCost();
		int have = count(sp);
		if (have < cost) { sp.sendSystemMessage(Messages.needHearts(cost, have), true); return InteractionResult.FAIL; }

		PlayerRecord after = s.state().restore(sp.getUUID());                          // 1. persist + flush FIRST
		HealthService.normalize(sp, after);                                            // 2. recompute + reapply
		HealthService.refill(sp);
		int removed = remove(sp, stack, cost);                                          // 3. THEN take the hearts
		if (removed != cost) s.state().audit().log(sp.getUUID(), sp.getGameProfile().name(), "RESTORE_SHORT", "expected " + cost + " removed " + removed);
		sp.getCooldowns().addCooldown(new ItemStack(Items.NETHER_STAR), Rules.HEART_USE_COOLDOWN_TICKS);
		sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
		sp.level().sendParticles(ParticleTypes.HEART, sp.getX(), sp.getY() + 1.0, sp.getZ(), 12, 0.6, 0.6, 0.6, 0.0);
		s.server().getPlayerList().broadcastSystemMessage(Messages.restoreBroadcast(sp.getGameProfile().name(), cost, after), false);
		return InteractionResult.SUCCESS;
	}
}
```

`BeaconPaymentSlotMixin.java`:
```java
package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Beacons accept any Nether Star; a Crimson Heart must never vanish into one. */
@Mixin(targets = "net.minecraft.world.inventory.BeaconMenu$PaymentSlot")
public abstract class BeaconPaymentSlotMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void hcheart$rejectHearts(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (HeartItem.isHeart(stack)) cir.setReturnValue(false);
	}
}
```

`IngredientMixin.java`:
```java
package com.fracturedhardcore.hcheart.mixin;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recipe matching ignores components; without this a Heart + glass + obsidian crafts a beacon. */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {
	@Inject(method = "test", at = @At("HEAD"), cancellable = true)
	private void hcheart$heartsAreNotIngredients(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (HeartItem.isHeart(stack)) cir.setReturnValue(false);
	}
}
```

`data/hcheart/recipe/crimson_heart.json`:
```json
{
	"type": "minecraft:crafting_shaped",
	"category": "misc",
	"pattern": ["ENE", "N*N", "ENE"],
	"key": {
		"E": "minecraft:echo_shard",
		"N": "minecraft:netherite_scrap",
		"*": "minecraft:nether_star"
	},
	"result": {
		"id": "minecraft:nether_star",
		"count": 1,
		"components": {
			"minecraft:custom_data": { "hcheart": true },
			"minecraft:custom_name": { "text": "Crimson Heart", "color": "red", "italic": false },
			"minecraft:rarity": "epic",
			"minecraft:enchantment_glint_override": true
		}
	}
}
```

`data/hcheart/advancement/crafted_crimson_heart.json` (hidden, repeatable craft broadcast):
```json
{
	"criteria": {
		"crafted": {
			"trigger": "minecraft:recipe_crafted",
			"conditions": { "recipe_id": "hcheart:crimson_heart" }
		}
	},
	"rewards": { "function": "hcheart:crafted" }
}
```

`data/hcheart/function/crafted.mcfunction`:
```
tellraw @a [{"text":"","color":"gold"},{"selector":"@s"},{"text":" crafted a Crimson Heart."}]
advancement revoke @s only hcheart:crafted_crimson_heart
```

Add `"BeaconPaymentSlotMixin", "IngredientMixin"` to the mixin config; `HeartItem.register();` to `HcHeartMod`; register `HeartGameTests`.

- [ ] **Step 4: Run** `./gradlew build` → PASS; check `build/run/gameTest/logs/latest.log` has no "Failed to parse" / recipe errors.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(heart): Crimson Heart recipe, consume handler with escalating cost, beacon and ingredient guards"`

---

### Task 9: Admin commands

**Files:**
- Create: `src/main/java/com/fracturedhardcore/hcheart/command/HcCommand.java`
- Modify: `HcHeartMod.java` (call `HcCommand.register()`)
- Create: `src/gametest/java/com/fracturedhardcore/hcheart/gametest/CommandGameTests.java`; register it

**Interfaces:**
- Produces: `/hc info [player]`, `/hc set <player> <deaths> <restores>`, `/hc reset all`, `/hc give <player> [count]`; `HcCommand.applyLive(Services, ServerPlayer)` (re-derives everything for an online player after a manual change).

- [ ] **Step 1: Write failing gametests**

`CommandGameTests.java`:
```java
package com.fracturedhardcore.hcheart.gametest;

import com.fracturedhardcore.hcheart.heart.HeartItem;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public class CommandGameTests {
	private static void run(GameTestHelper helper, String command) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	@GameTest
	public void setWritesCountersAndReappliesLiveState(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_set", new Vec3(4, 2, 4));
		try {
			run(helper, "hc set cmd_set 2 1");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 2, "deaths");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 1, "restores");
			helper.assertValueEqual((double) p.getMaxHealth(), 12.0, "live max health re-derived");
			run(helper, "hc set cmd_set 4 0");
			helper.assertValueEqual(p.gameMode(), GameType.SPECTATOR, "eliminated by admin -> spectator");
			run(helper, "hc set cmd_set 0 0");
			helper.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "rescued");
			helper.assertValueEqual((double) p.getMaxHealth(), 20.0, "full cap");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void resetAllWipesEveryoneAndNormalisesOnlinePlayers(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_reset", new Vec3(4, 2, 4));
		try {
			run(helper, "hc set cmd_reset 3 2");
			p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(18.0);
			p.setGameMode(GameType.SPECTATOR);
			run(helper, "hc reset all");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).deaths(), 0, "deaths wiped");
			helper.assertValueEqual(Hc.state().get(p.getUUID()).restoresUsed(), 0, "restores wiped");
			helper.assertValueEqual(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 20.0, "base normalised");
			helper.assertValueEqual(p.getHealth(), 20.0f, "full health");
			helper.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "back to survival");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}

	@GameTest
	public void giveHandsOutHeartsAndInfoRuns(GameTestHelper helper) {
		ServerPlayer p = TestPlayers.join(helper, "cmd_give", new Vec3(4, 2, 4));
		try {
			run(helper, "hc give cmd_give 3");
			helper.assertValueEqual(HeartItem.count(p), 3, "three hearts given");
			run(helper, "hc give cmd_give");
			helper.assertValueEqual(HeartItem.count(p), 4, "default count is 1");
			run(helper, "hc info cmd_give");
		} finally { TestPlayers.leave(p); }
		helper.succeed();
	}
}
```

- [ ] **Step 2: Run** → compilation error / command unknown.

- [ ] **Step 3: Implement**

`HcCommand.java`:
```java
package com.fracturedhardcore.hcheart.command;

import java.util.Collection;
import com.fracturedhardcore.hcheart.HcHeartMod;
import com.fracturedhardcore.hcheart.Services;
import com.fracturedhardcore.hcheart.core.PlayerRecord;
import com.fracturedhardcore.hcheart.death.Messages;
import com.fracturedhardcore.hcheart.downed.Text;
import com.fracturedhardcore.hcheart.health.HealthService;
import com.fracturedhardcore.hcheart.heart.HeartItem;
import com.fracturedhardcore.hcheart.join.RespawnService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;

public final class HcCommand {
	private static final PermissionCheck OP = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);
	private HcCommand() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(Commands.literal("hc")
				.then(Commands.literal("info")
						.executes(ctx -> infoSelf(ctx))
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).requires(Commands.hasPermission(OP)).executes(ctx -> info(ctx))))
				.then(Commands.literal("set").requires(Commands.hasPermission(OP))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.then(Commands.argument("deaths", IntegerArgumentType.integer(0))
										.then(Commands.argument("restores", IntegerArgumentType.integer(0)).executes(ctx -> set(ctx))))))
				.then(Commands.literal("reset").requires(Commands.hasPermission(OP))
						.then(Commands.literal("all").executes(ctx -> resetAll(ctx))))
				.then(Commands.literal("give").requires(Commands.hasPermission(OP))
						.then(Commands.argument("player", EntityArgument.player())
								.executes(ctx -> give(ctx, 1))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))));
	}

	private static Services services(CommandContext<CommandSourceStack> ctx) {
		Services s = HcHeartMod.services();
		if (s == null) ctx.getSource().sendFailure(Component.literal("Fractured Hardcore is not ready yet."));
		return s;
	}

	private static int infoSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx); if (s == null) return 0;
		ServerPlayer self = ctx.getSource().getPlayerOrException();
		ctx.getSource().sendSuccess(() -> describe(s, self.getGameProfile().name(), s.state().get(self.getUUID())), false);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx); if (s == null) return 0;
		for (NameAndId profile : GameProfileArgument.getGameProfiles(ctx, "player")) {
			PlayerRecord rec = s.state().get(profile.id());
			ctx.getSource().sendSuccess(() -> describe(s, profile.name(), rec), false);
		}
		return 1;
	}

	private static Component describe(Services s, String name, PlayerRecord rec) {
		String status = rec.eliminated() ? "ELIMINATED" : rec.finalLife() ? "final life" : "alive";
		String downed = rec.isDowned() ? " · DOWNED, " + Text.mmss(rec.downedTicksRemaining(s.state().now())) + " left" : "";
		return Text.info(name + ": deaths " + rec.deaths() + " · restores used " + rec.restoresUsed() + " · max " + rec.maxHearts() + " hearts · next Heart costs " + Messages.hearts(rec.restoreCost()) + " · " + status + downed);
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Services s = services(ctx); if (s == null) return 0;
		int deaths = IntegerArgumentType.getInteger(ctx, "deaths"), restores = IntegerArgumentType.getInteger(ctx, "restores");
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
		for (NameAndId profile : profiles) {
			s.state().getOrCreate(profile.id(), profile.name());
			PlayerRecord rec = s.state().set(profile.id(), deaths, restores, ctx.getSource().getTextName());
			ServerPlayer online = s.server().getPlayerList().getPlayer(profile.id());
			if (online != null) applyLive(s, online);
			ctx.getSource().sendSuccess(() -> describe(s, profile.name(), rec), true);
		}
		return profiles.size();
	}

	private static int resetAll(CommandContext<CommandSourceStack> ctx) {
		Services s = services(ctx); if (s == null) return 0;
		int count = s.state().all().size();
		s.state().resetAll(ctx.getSource().getTextName());
		for (ServerPlayer online : s.server().getPlayerList().getPlayers()) {
			s.state().getOrCreate(online.getUUID(), online.getGameProfile().name());
			applyLive(s, online);
			HealthService.refill(online);
		}
		ctx.getSource().sendSuccess(() -> Text.gold("Fractured Hardcore: wiped " + count + " record(s); everyone is back to 10 hearts."), true);
		return 1;
	}

	private static int give(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
		Services s = services(ctx); if (s == null) return 0;
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		ItemStackGive.give(target, HeartItem.create(count));
		s.state().audit().log(target.getUUID(), target.getGameProfile().name(), "GIVE", count + " heart(s) by " + ctx.getSource().getTextName());
		ctx.getSource().sendSuccess(() -> Text.gold("Gave " + Messages.hearts(count) + " to " + target.getGameProfile().name() + "."), true);
		return count;
	}

	/** Re-derive everything for an online player after a manual state change (mirrors the join handler). */
	public static void applyLive(Services s, ServerPlayer player) {
		PlayerRecord rec = s.state().get(player.getUUID());
		s.downed().clear(player, "admin");
		HealthService.normalize(player, rec);
		if (rec.eliminated()) {
			if (!player.isSpectator()) player.setGameMode(GameType.SPECTATOR);
		} else if (player.isSpectator()) {
			player = RespawnService.rescueFromSpectator(player);
		}
		s.state().scoreboard().sync(rec);
	}

	private static final class ItemStackGive {
		static void give(ServerPlayer target, net.minecraft.world.item.ItemStack stack) {
			if (!target.getInventory().add(stack)) target.drop(stack, false);
		}
	}
}
```

Register `HcCommand.register();` in `HcHeartMod` and `CommandGameTests` in the gametest `fabric.mod.json`.

- [ ] **Step 4: Run** `./gradlew build` → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(command): /hc info|set|reset all|give"`

---

### Task 10: README, backup script, final verification

**Files:**
- Modify: `README.md`
- Create: `scripts/backup.sh`

- [ ] **Step 1: README** — sections: what the mod does (player rules: downed, revive, true death ladder, final life, Crimson Heart recipe + why echo shards set the price + escalating cost), admin guide (install on a 26.2 Fabric server with Fabric API; run `/hc reset all` once before the first session; the join handler repairs skillux's base value and the stranded spectators automatically; audit log location `logs/hcheart-audit.log`; state file `<world>/data/hcheart/players.dat` — verify the actual path from the gametest run and write the real one), commands table, known limitations (client hardcore flag staleness within a session; downed player's own camera stays at standing height in open areas; Absorption/Health Boost stack), design decisions summary, building and testing (`./gradlew build` runs unit tests + gametests). Fix the heart ladder line to `10 → 8 → 6 → 4`.

- [ ] **Step 2: `scripts/backup.sh`** — rolling backup using rcon (`mcrcon`) `save-off`, `save-all flush`, `tar`, `save-on`, keeping the newest N archives; usage comment with a cron line `0 * * * *`. Mark executable.

```bash
#!/usr/bin/env bash
# Rolling world backup for a Fabric dedicated server. Requires: enable-rcon=true + rcon.password in server.properties, and `mcrcon` on PATH.
# Usage: scripts/backup.sh <server-dir> <backup-dir> [keep=48]      Cron (hourly): 0 * * * * /path/to/backup.sh /srv/mc /srv/backups 48
set -euo pipefail
SERVER_DIR="${1:?server dir}"; BACKUP_DIR="${2:?backup dir}"; KEEP="${3:-48}"
RCON_PORT=$(grep -E '^rcon.port=' "$SERVER_DIR/server.properties" | cut -d= -f2); RCON_PORT="${RCON_PORT:-25575}"
RCON_PASS=$(grep -E '^rcon.password=' "$SERVER_DIR/server.properties" | cut -d= -f2-)
LEVEL=$(grep -E '^level-name=' "$SERVER_DIR/server.properties" | cut -d= -f2); LEVEL="${LEVEL:-world}"
rcon() { mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PASS" "$@"; }
mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
rcon "save-off" "save-all flush" >/dev/null
sleep 5
trap 'rcon "save-on" >/dev/null || true' EXIT
tar -C "$SERVER_DIR" -czf "$BACKUP_DIR/$LEVEL-$STAMP.tar.gz" "$LEVEL" "logs/hcheart-audit.log" 2>/dev/null || tar -C "$SERVER_DIR" -czf "$BACKUP_DIR/$LEVEL-$STAMP.tar.gz" "$LEVEL"
ls -1t "$BACKUP_DIR"/"$LEVEL"-*.tar.gz | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "backup written: $BACKUP_DIR/$LEVEL-$STAMP.tar.gz"
```

- [ ] **Step 3: Final verification** — `./gradlew clean build --console=plain`; confirm: unit tests passed (`build/reports/tests/test/index.html`), gametests passed (count in `build/run/gameTest/logs/latest.log`, look for "All required tests passed" / the per-test lines), no "Failed to parse"/"Couldn't load" lines for `hcheart` data in that log, jar at `build/libs/fractured-hardcore-0.1.0.jar` contains `data/hcheart/recipe/crimson_heart.json` (`unzip -l`).

- [ ] **Step 4: Commit** — `git add -A && git commit -m "docs: README, backup script"`
