ShadowCore 3.0 — Spec Amendments and Clarifications
===================================================

This document records the specific points where the implementation diverges
from the literal text of the normative specification, with rationale. The
implementation remains faithful to the spec's intent everywhere; these notes
flag places where I made judgment calls rather than guessing.

## A1. The PHANTOM mode (addendum to §7 and §14)

The spec at §7 defines four stable controller session states:
MAIN_MOUNTED, LOCAL_PROFILE_MOUNTED, SHADOW_MOUNTED, CONFLICT_SPECTATOR_SHELL.
The user clarified after the spec was written that shadowing one's own main
account name produces a distinct behavior: invisibility with a default skin
and no name, rendered only to hack clients.

This is not really "shadow mode with target=self" — it has different
presentation semantics, no second body is needed, and no tab entry should
exist anywhere. I modeled it as a new kind of mount, **PHANTOM**, orthogonal
to the four session states. A PHANTOM mount can sit on top of either a MAIN
or LOCAL_PROFILE baseline. The stable session state is still SHADOW_MOUNTED
(all the command-state-matrix rules of §11 still apply), but the kind on
`MountedIdentity` disambiguates presentation.

## A2. Self-shadow from a local profile (§17.4 refinement)

§17.4 says self-shadow can target the controller account name OR the
baseline local profile name. The user's clarification was specifically about
the main account name, but the phantom logic works identically from a
local-profile baseline: `/shadow <my-local-profile-name>` while baseline
is that local profile → PHANTOM mount on the local profile. I implemented
both uniformly.

## A3. Rule 2.21 reinterpretation (multi-login queue)

Rule 2.21 says "Multiple log ins are now allowed on the server up to the
point all profiles are loaded. Added to the consumption queue in a first-in-
last-out order based on log offs."

I interpret this as a **session stack per controller**, not as literal
concurrent Mojang sessions for the same account (which Mojang's auth server
does not permit — a second successful login evicts the first). The useful
semantic is: while a controller is mounted on local profile A, they can
switch to local profile B; on `/lprofile logout` they pop back to A rather
than jumping straight to main. This is the FILO "consumption queue based on
log offs" reading.

I did NOT implement the session stack in 3.0. The current behavior matches
the simpler "commit on switch, swap mount" model from §9.1. If you want the
stack behavior, extend `ProfileIdentity` with a parent-mount reference and
have `Manager.onProfileSwitch` push rather than replace. This is a local
change in `Manager` and does not affect the NMS layer.

## A4. "Auth takeover" scope (Rule 2.22 scope)

Rule 2.22 says "The mod will take full control over server authentication,
pass through normal auth, provide name resolution to plugins for alt
profiles." I implemented the **name-resolution-authority** half of this
(AuthGateway pre-populates GameProfileCache; local profile names resolve to
the right synthetic UUID during pre-login). I did NOT intercept or replace
Mojang session verification — that would be wrong; vanilla's auth is
correct for real accounts.

"Full control" here means "the mod is the authoritative source for what
name X resolves to", which is an outcome, not a mechanism. The outcome is
delivered through AuthGateway's GameProfileCache manipulation and its
AsyncPlayerPreLoginEvent handler that rewrites name collisions.

## A5. DISCARD disposition implementation (Rule 2.11)

Rule 2.11 says "A shadow session may discard mounted changes." The literal
mechanism vanilla provides is `PlayerList.remove()`, which always saves.
My implementation of DISCARD is:

  1. At mount time, `MountBackup.capture(uuid)` copies the on-disk .dat.
  2. On DISCARD unmount, `MountBackup.restore(uuid)` overwrites the freshly-
     saved .dat with the backup, undoing vanilla's save.

This keeps spec §2.6 ("backend must not manually edit a vanilla player
data file as a normal operation") satisfied in spirit — the normal path is
the vanilla save; the backup/restore is an explicit DISCARD operation, not
a normal one.

## A6. Forbidden use of PlayerList.placeNewPlayer for the mounted side

The spec's core idea — two real ServerPlayer instances per controller —
cannot use `PlayerList.placeNewPlayer` because that API assumes a freshly-
authenticated Connection and performs configuration-phase handshakes the
controller's real connection has already completed. Instead, `MountKernel`
replicates the subset of `placeNewPlayer` we need:

  * `PlayerList.load(doll)` reads the .dat into the mounted ServerPlayer.
  * `ServerLevel.addNewPlayer(doll)` registers the entity.
  * `PlayerListAccess.registerMounted` adds the doll to the internal
    players/playersByUUID/playersByName collections via reflection.
  * A headless `Connection` (subclass of `net.minecraft.network.Connection`)
    routes the mounted-side's outbound packets to the controller's real
    connection rather than a dead socket.

This is the single most fragile part of the implementation and is called
out in `ARCHITECTURE.md` and `KNOWN_ISSUES.md`. If Paper adds a public API
for "register an already-loaded ServerPlayer without running login", this
entire reflective path should be replaced with that.

## A7. Spectator-shell implementation (§13)

§13 says the controller parks in spectator while the baseline restore is
deferred. The cleanest implementation would be a truly inert "shell" body
that doesn't interact with world ticks at all, but spectator mode is the
closest vanilla primitive and has the right semantics: no world ownership,
can be teleported, no gameplay interactions. My implementation uses
`GameType.SPECTATOR` on the controller's own ServerPlayer.

A side effect I accept: the controller IS a valid observer while in the
shell — they can see through walls, fly, etc. This is unavoidable without
patching vanilla's spectator behavior. Operators who want to prevent
abuse during shell state can teleport the controller to a safe room on
shell entry via an extension listener on `ShadowConflictArrived`.
