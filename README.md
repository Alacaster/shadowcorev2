ShadowCore 3.0
==============

Paper plugin implementing the controller-and-mounted-doll identity
orchestration described in the ShadowCore behavior specification.

## Layout

```
build.gradle.kts                    paperweight-userdev, Mojang-mapped, 1.21.11
settings.gradle.kts
ARCHITECTURE.md                     design doc — two-ServerPlayer model
SPEC_AMENDMENTS.md                  implementation vs spec deltas (A1–A7)
src/main/resources/
  paper-plugin.yml                  plugin manifest
  config.yml                        defaults + skin-cache TTL
src/main/java/dev/shadowcore/
  ShadowCorePlugin.java             bootstrap / wiring
  model/                            value types
    ProfileIdentity, MountedIdentity (Kind: MAIN/LOCAL/SHADOW/PHANTOM),
    SessionState, MountDisposition
  util/
    Naming.java                     suffix validation + §2.3 reconstruction
  store/
    Database.java                   SQLite, orchestration-only, legacy migration
  engine/
    EngineEvent.java                sealed event hierarchy
    EventEngine.java                main-thread FIFO drain
    ResponseHandle.java             user-reply sink
  manager/
    Manager.java                    §11 command-by-state + §12 automatic rules
  core/
    auth/
      AuthGateway.java              pre-login rewrite + GameProfileCache priming
      SkinResolver.java             async Mojang sessionserver skin fetcher
    nms/
      DualPlayerSession.java        per-controller controller+mounted pairing
      DualPlayerRegistry.java       UUID → session lookup
      HeadlessConnection.java       mounted-side Connection forwarding sends
      PacketClassifier.java         connection-essential vs gameplay
      PacketRouter.java             inbound routing + outbound mute
      PlayerListAccess.java         reflective insertion into PlayerList
      MountBackup.java              pre-mount .dat backup for DISCARD
      MountKernel.java              mount/swap/unmount/reload/persist
  presentation/
    PresentationService.java        tab/world policy per Kind
    SkinCloak.java                  ProtocolLib PlayerInfo rewriter (phantom)
  listener/
    PlayerLifecycleListener.java    join/quit/gamemode → EngineEvent
    ShadowConflictDetector.java     §10 real-target-activation priority
  command/
    LProfileCommand.java, ShadowCommand.java
```

## Building

```
./gradlew build
```

Output JAR: `build/libs/shadowcore-3.0.0-SNAPSHOT.jar`. Paper 1.21.11+.
ProtocolLib is optional (PHANTOM hack-client resistance needs it).

## Runtime contract

The plugin maintains two NMS ServerPlayer instances per connected
controller: the real controller-side player (permission authority, real
Netty connection) and a mounted-side "doll" (world actor, vanilla
ownership target, vanilla save/load target). When the mount changes, the
old doll is removed through PlayerList.remove (optionally with MountBackup
undoing vanilla's save for DISCARD), and a new doll is constructed and
registered. See ARCHITECTURE.md.

## Key spec citations

See SPEC_AMENDMENTS.md for the full list of divergences. Headlines:

  * PHANTOM mode is a new `MountedIdentity.Kind` orthogonal to §7 stable
    states, added to model self-shadow-of-main with identity hiding.
  * Rule 2.21 (multi-login stack) is deliberately NOT implemented; its
    natural implementation point is `Manager.onProfileSwitch` — amend to
    push-rather-than-replace and teach `onControllerQuit` to pop.
  * Rule 2.22 ("auth takeover") is implemented as name-resolution authority
    via GameProfileCache priming + pre-login rewrite, not by replacing the
    session verification path.

## Known issues and runtime-validation risk

See top of `MountKernel.java` javadoc. The two things most likely to need
adjustment against the real 1.21.11 NMS:

  1. `PlayerListAccess` uses reflective field access on `players`,
     `playersByUUID`, `playersByName`. If any field name drifts, this
     breaks at runtime.
  2. `MountKernel` replicates the subset of `PlayerList.placeNewPlayer`
     we need (load, addNewPlayer, register, broadcast ADD_PLAYER) because
     `placeNewPlayer` itself cannot be called with a non-fresh Connection.

The build is untested; expect some field-name / import-path cleanup on
first build against real userdev bundles.
