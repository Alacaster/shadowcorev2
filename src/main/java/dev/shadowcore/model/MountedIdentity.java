package dev.shadowcore.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity that the world and other plugins must treat as the acting
 * player. It is always exactly one of:
 *
 * <ul>
 *   <li>{@link Kind#MAIN}    — the controller's main account identity.</li>
 *   <li>{@link Kind#LOCAL}   — a local profile identity owned by the controller.</li>
 *   <li>{@link Kind#SHADOW}  — a shadow target identity distinct from the controller.</li>
 *   <li>{@link Kind#PHANTOM} — a self-shadow. The world actor remains the
 *       baseline (controller) ServerPlayer, but the presentation layer
 *       enforces complete identity hiding: removed from every tab list,
 *       invisible to legitimate clients, default skin and empty name to
 *       any client that bypasses the PlayerInfo filter (hack clients).
 *       The controller can still physically interact with the world — the
 *       phantom is the controller, just cloaked.</li>
 * </ul>
 *
 * <p>For SHADOW and PHANTOM kinds, {@link #baseline()} carries the identity
 * that will be restored after the shadow ends. For MAIN and LOCAL,
 * {@link #baseline()} equals the mounted identity itself.</p>
 *
 * <h2>Why PHANTOM is distinct from SHADOW with selfShadow=true</h2>
 * A shadow of "yourself-as-yourself" has no separate mountable identity to
 * switch to. Constructing a second ServerPlayer with the same UUID as the
 * controller would immediately collide in PlayerList. PHANTOM says: "Don't
 * build a second ServerPlayer; keep the controller as the world actor; apply
 * maximum presentation-level invisibility."
 */
public final class MountedIdentity {
    public enum Kind { MAIN, LOCAL, SHADOW, PHANTOM }

    private final Kind kind;
    private final UUID uuid;
    private final String displayName;
    private final ProfileIdentity baseline;
    /** For SHADOW/PHANTOM: the name that was requested, used for reservations. */
    private final String shadowTargetName;
    /** For SHADOW/PHANTOM: whether this is a self-shadow (target UUID == baseline UUID). */
    private final boolean shadowSelf;

    private MountedIdentity(final Kind kind, final UUID uuid, final String displayName,
                            final ProfileIdentity baseline,
                            final String shadowTargetName, final boolean shadowSelf) {
        this.kind = Objects.requireNonNull(kind);
        this.uuid = Objects.requireNonNull(uuid);
        this.displayName = Objects.requireNonNull(displayName);
        this.baseline = Objects.requireNonNull(baseline);
        this.shadowTargetName = shadowTargetName;
        this.shadowSelf = shadowSelf;
    }

    public static MountedIdentity ofBaseline(final ProfileIdentity baseline, final String displayName) {
        final Kind kind = baseline.isMain() ? Kind.MAIN : Kind.LOCAL;
        return new MountedIdentity(kind, baseline.worldActorUuid(), displayName, baseline, null, false);
    }

    /**
     * Construct a SHADOW mount. Callers must not use this when the target
     * UUID equals the baseline's world-actor UUID — use {@link #ofPhantom}
     * instead. The manager layer makes that determination.
     */
    public static MountedIdentity ofShadow(final ProfileIdentity baseline,
                                           final UUID targetUuid, final String targetName) {
        if (targetUuid.equals(baseline.worldActorUuid())) {
            throw new IllegalArgumentException(
                "self-shadow must use ofPhantom(), not ofShadow() — target UUID equals baseline UUID");
        }
        return new MountedIdentity(Kind.SHADOW, targetUuid, targetName, baseline, targetName, false);
    }

    /**
     * Construct a PHANTOM mount — self-shadow where the controller is its
     * own target. The world actor is the baseline identity; the presentation
     * layer enforces identity hiding and invisibility.
     */
    public static MountedIdentity ofPhantom(final ProfileIdentity baseline, final String targetName) {
        return new MountedIdentity(Kind.PHANTOM, baseline.worldActorUuid(), targetName, baseline, targetName, true);
    }

    public Kind kind() { return kind; }
    public UUID uuid() { return uuid; }
    public String displayName() { return displayName; }
    public ProfileIdentity baseline() { return baseline; }
    public boolean isShadow() { return kind == Kind.SHADOW || kind == Kind.PHANTOM; }
    public boolean isPhantom() { return kind == Kind.PHANTOM; }
    public String shadowTargetName() { return shadowTargetName; }
    public boolean shadowSelf() { return shadowSelf; }

    @Override public String toString() {
        return "MountedIdentity[" + kind + " " + displayName + " " + uuid + "]";
    }
}
