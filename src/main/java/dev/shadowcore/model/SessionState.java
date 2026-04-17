package dev.shadowcore.model;

/**
 * The five stable controller session states from spec §7.
 *
 * <p>A "stable" state persists across ticks and is directly experienceable by
 * the human controller. Transitional states (CHECKING_NAME, placing, etc.)
 * live in {@link dev.shadowcore.manager.IntentionState}, not here.</p>
 */
public enum SessionState {
    /** Spec §7: "The controller player connection is offline." */
    DISCONNECTED,
    /** Spec §7: main account identity is mounted, no shadow. */
    MAIN_MOUNTED,
    /** Spec §7: local profile identity is mounted, no shadow. */
    LOCAL_PROFILE_MOUNTED,
    /** Spec §7: shadow session is active on some baseline. */
    SHADOW_MOUNTED,
    /** Spec §7: forced shadow end occurred; baseline restore deferred. */
    CONFLICT_SPECTATOR_SHELL
}
