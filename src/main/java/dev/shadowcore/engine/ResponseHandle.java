package dev.shadowcore.engine;

import java.util.function.Consumer;

/**
 * Abstracts the destination for user-visible responses to an event.
 *
 * <p>Commands produce a handle that dispatches to the controller's chat.
 * Automatic events produce a silent handle. Unit tests (when we have them)
 * produce a capturing handle.</p>
 *
 * <p>The message string uses MiniMessage-style tags; the CommandSender
 * adapter in the command module converts to a {@link
 * net.kyori.adventure.text.Component}.</p>
 */
@FunctionalInterface
public interface ResponseHandle {
    void reply(String miniMessage);

    static ResponseHandle silent() { return m -> {}; }
    static ResponseHandle of(final Consumer<String> sink) { return sink::accept; }
}
