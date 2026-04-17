package dev.shadowcore.engine;

import java.util.UUID;

/**
 * Every event that can enter the orchestrator event queue. Exhaustive —
 * anything the manager layer needs to react to is a case here, so that
 * matches are compile-time checked.
 *
 * <p>Events are value objects with no behavior. The {@link
 * dev.shadowcore.manager.IntentionResolver} dispatches on the sealed type.</p>
 */
public sealed interface EngineEvent {

    /** The controller-UUID the event pertains to. Never null. */
    UUID actor();

    /** Where to send user-visible responses. Never null (can be a no-op sink). */
    ResponseHandle response();

    // ──────────────────────────────────────────────────────────────────
    //  Local profile command events
    // ──────────────────────────────────────────────────────────────────
    record ProfileCreate(UUID actor, String actorName, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileSwitch(UUID actor, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileList(UUID actor, ResponseHandle response) implements EngineEvent {}
    record ProfileRename(UUID actor, String oldSuffix, String newSuffix, ResponseHandle response) implements EngineEvent {}
    record ProfileDelete(UUID actor, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileStatus(UUID actor, ResponseHandle response) implements EngineEvent {}
    record ProfileDiscard(UUID actor, ResponseHandle response) implements EngineEvent {}
    record ProfileReturnToMain(UUID actor, ResponseHandle response) implements EngineEvent {}
    record ProfileSetLimit(UUID admin, UUID target, int limit, ResponseHandle response) implements EngineEvent {
        @Override public UUID actor() { return admin; }
    }
    record ProfileAdminDelete(UUID admin, UUID owner, String suffix, ResponseHandle response) implements EngineEvent {
        @Override public UUID actor() { return admin; }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Shadow command events
    // ──────────────────────────────────────────────────────────────────
    record ShadowMount(UUID actor, String actorName, String targetName, ResponseHandle response) implements EngineEvent {}
    record ShadowLogout(UUID actor, boolean resetLocation, ResponseHandle response) implements EngineEvent {}
    record ShadowDiscard(UUID actor, ResponseHandle response) implements EngineEvent {}
    record ShadowStatus(UUID actor, ResponseHandle response) implements EngineEvent {}

    // ──────────────────────────────────────────────────────────────────
    //  Lifecycle & automatic events (spec §12)
    // ──────────────────────────────────────────────────────────────────

    /** The controller player connection just joined. */
    record ControllerJoin(UUID actor, String actorName, ResponseHandle response) implements EngineEvent {}
    /** The controller player connection is about to close. */
    record ControllerQuit(UUID actor, ResponseHandle response) implements EngineEvent {}
    /**
     * A real account identity just activated (logged in) while this controller
     * was shadowing that identity. Priority rule from spec §10: real activation
     * wins over shadow continuation.
     */
    record ShadowConflictArrived(UUID actor, String arrivingName, UUID arrivingUuid, ResponseHandle response) implements EngineEvent {}
    /**
     * The conflict target disconnected while this controller was still in the
     * conflict spectator shell. Per §12, we clear the spectator follow target
     * but keep the deferred restore pending.
     */
    record ShadowConflictTargetLeft(UUID actor, ResponseHandle response) implements EngineEvent {}
    /**
     * The controller attempted to leave spectator while in conflict shell.
     * The deferred-restore trigger per §13.
     */
    record ControllerGameModeChange(UUID actor, String toMode, ResponseHandle response) implements EngineEvent {}

    // ──────────────────────────────────────────────────────────────────
    //  Async resolution events (e.g., name lookups completing)
    // ──────────────────────────────────────────────────────────────────
    record ShadowNameResolved(UUID actor, String actorName, String targetName, boolean isReal, UUID resolvedUuid, ResponseHandle response) implements EngineEvent {}
}
