ShadowCore 3.0 — Architecture
=============================

This file is a design reference, not a runtime artifact.

## The central idea

Every controller connection simultaneously owns TWO `net.minecraft.server.level.ServerPlayer`
instances:

  * **Controller-side**: the real NMS player created during vanilla login. It keeps
    its real Netty `Connection` (the one the client is actually talking to). It is
    the **permission authority**: command dispatch, OP status, and safety-critical
    operations (unmount, discard) always consult this side.

  * **Mounted-side (the "doll")**: an NMS player created by us. It has the UUID,
    name, and GameProfile of the *mounted identity* (main / local-profile / shadow
    target). It is added to the world through `ServerLevel.addNewPlayer` and
    registered in `PlayerList.players` just like a normal login would. Its
    `ServerGamePacketListenerImpl` is wired to a dummy `Connection` in
    `PacketFlow.CLIENTBOUND` whose packet pipe is *redirected* by us to the
    controller's real `Connection`. Its vanilla save/load paths fire normally,
    so inventory, location, ender chest, advancements, statistics, abilities,
    owned wolves, thrown pearls, beds, all belong to the mounted UUID via
    `world/playerdata/<mounted-uuid>.dat` without us ever touching a .dat file
    by hand.

These two `ServerPlayer`s exist for the lifetime of the mount. When the mount
changes, the old mounted-side is removed via the normal `PlayerList.remove`
path (vanilla writes its .dat), and a new mounted-side is constructed and
placed via `PlayerList.placeNewPlayer` (vanilla reads its .dat). The controller
side is unaffected.

## Packet routing

The controller-side `ServerPlayer.connection` is muted: we replace its send
pipe with a no-op the instant a mount activates, because the client must not
see controller-entity updates directly — the client sees only the doll.

Serverbound packets arriving on the real `Connection` are intercepted inside
Netty's `ChannelPipeline`. Movement, interaction, chat, digging, and action
packets are forwarded to the mounted-side's `ServerGamePacketListenerImpl`
so the doll experiences the input as its own. A small handful of packets
(keepalive, client settings, client information, plugin messages) remain bound
to the controller-side because they relate to the physical connection, not
the doll.

Clientbound packets that the mounted-side would have sent (through its dummy
Connection) are captured by a substitute `PacketSendListener`-equivalent and
forwarded into the real `Connection.send`. Entity IDs are rewritten where
needed so that the self-view (the "owner" entity ID) points at the doll's
entity ID — vanilla already produces this correctly from the doll's listener,
so no rewriting is typically necessary for self-view packets. The rewriting
that IS necessary is on PlayerInfo packets so that the doll is visible in the
tab list (local-profile mode) or hidden (shadow mode), per policy.

## Why not Mixin

Paper plugins cannot load Fabric Mixin. Paper 1.21.11 supports direct NMS
access through paperweight-userdev with Mojang mappings — that gives us
everything Mixin would: real types, no reflection, compile-time checked.

## Auth takeover (Rule 2.22)

Every Paper plugin sees a `PlayerLoginEvent`/`PlayerJoinEvent`. We hook into
`AsyncPlayerPreLoginEvent` to resolve local-profile suffixes, synthetic
identities, and shadow alt-logins *before* vanilla runs its "already online"
check, and into `PlayerJoinEvent` to immediately pivot a freshly-joined
controller into its stored desired mount state. We do not replace the login
listener; we steer it.

For the LIFO "second-login on the same account name" requested in Rule 2.21,
we intercept the `AsyncPlayerPreLoginEvent` for the alt-login, disallow it as
a real second connection, and instead queue it as a mount request against the
existing controller's connection. (In practice, a second client connecting
with the same Mojang account is extremely rare and exists mostly to support
"log in on my phone as my alt while on my PC" ergonomics.)

## Name resolution (Section 2.4 of sync spec)

The mod is authoritative for UUID↔name mapping for local profiles and
synthetic identities. We hook into every place the server or other plugins
look up names:

  * `Bukkit.getOfflinePlayer(String)` / `(UUID)` — via Paper's
    `NameEntryEvent`-adjacent paths and an explicit service used internally.
  * `PlayerList.getPlayer(String)` — irreducible; we ensure the mounted-side
    `ServerPlayer` is registered with the reconstructed display name so vanilla
    answers correctly.
  * GameProfile cache — we pre-populate entries for local-profile UUIDs via
    `GameProfileCache.add`, so vanilla code (chunk loaders, ownership lookups,
    etc.) resolves correctly without external Mojang queries.

## Layer map

  * `dev.shadowcore.core.native` — NMS-level dual-ServerPlayer kernel.
  * `dev.shadowcore.core.packet` — Netty/NMS packet routing and rewriting.
  * `dev.shadowcore.core.auth` — login interception and name-resolution authority.
  * `dev.shadowcore.engine` — the event queue (main-thread-only).
  * `dev.shadowcore.manager` — intention → resolved-desired-state → reconcile plan.
  * `dev.shadowcore.store` — SQLite orchestration store (no gameplay payload).
  * `dev.shadowcore.presentation` — tab-list + visible-name + skin policy.
  * `dev.shadowcore.commands` — slash command surface.
  * `dev.shadowcore.model` — value types (MountedIdentity, ProfileIdentity, etc.).
