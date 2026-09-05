# Contributing

Thanks for helping keep five hardcore characters alive. The full developer guide lives in the
[README](README.md#for-contributors-and-agents); this file is the short version.

1. Read [README → Architecture](README.md#architecture) and [README → Invariants](README.md#invariants-do-not-break-these)
   before touching the death path. Reliability outranks features here.
2. Every behaviour change ships with a test: a JUnit test if it lives in `core/`, a gametest otherwise.
3. Run `./gradlew build` (JDK 25). It must be green: it compiles, runs the unit tests, and runs the gametest server.
4. Keep commits small and conventional (`feat(downed): …`, `fix(heart): …`, `docs: …`, `test: …`).
5. **Update `README.md` in the same change** whenever you alter behaviour, commands, files, constants, hook points,
   tests or the toolchain. The README must always describe the code as it is on `main`.
6. If you deviate from `docs/superpowers/specs/2026-09-05-fractured-hardcore-design.md`, add the decision and its
   reason to the spec's "Decisions and deviations" section.

Bug reports: include the relevant lines from `logs/hcheart-audit.log`, the server log, and the output of `/hc info <player>`.
