package dev.shadowcore.core.nms;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A {@link Connection} backed by a local {@link EmbeddedChannel} that
 * performs no real I/O.
 *
 * <p>Used for parked baselines during shadow sessions. While a controller
 * is shadowing, their baseline {@code ServerPlayer} stays in the world
 * (so its UUID resolves for pets, ender pearls, plugins) but is not
 * driven by any real socket. Its {@code connection} field points at one
 * of these instances. Anything Paper does that touches the connection
 * goes into a black hole rather than affecting the controller's real
 * client socket.</p>
 *
 * <p>Each parked baseline has its own {@code NoopConnection} instance.
 * The instance is never shared with a real client.</p>
 *
 * <p>Failure modes: any Paper internal that calls a non-overridden method
 * on this object will hit the parent {@code Connection} implementation,
 * which may itself try to do something with the embedded channel. Since
 * the embedded channel is a real Netty channel (just not bound to a
 * socket), most operations either no-op cleanly or write to the embedded
 * channel's outbound queue, which we never read from. If a specific
 * Paper version calls a method that throws on the embedded channel, we
 * can override it here.</p>
 */
public final class NoopConnection extends Connection {

    public NoopConnection() {
        super(PacketFlow.SERVERBOUND);
        // Wire up a fresh EmbeddedChannel. The Connection superclass uses
        // its `channel` field for I/O; pointing it at an EmbeddedChannel
        // means writes accumulate in the channel's outbound buffer rather
        // than going to a socket. We never drain that buffer; it just
        // grows slowly. For the duration of a shadow session this is
        // bounded — Paper's per-tick traffic to a player who isn't doing
        // anything is minimal.
        final EmbeddedChannel ch = new EmbeddedChannel();
        // Connection.channelActive(ChannelHandlerContext) wires `this.channel`
        // by calling fireChannelActive on the pipeline. We invoke the
        // pipeline manually so the field gets populated.
        ch.pipeline().addLast("packet_handler", this);
        // EmbeddedChannel is active as soon as it's constructed, so the
        // handlerAdded callback already fired and `channel` is set.
    }

    /** No real socket — always offline. */
    @Override
    public boolean isConnected() {
        return false;
    }

    /**
     * Drop outbound packets on the floor. Paper's tick loop and various
     * broadcast paths will try to push packets to this connection; for a
     * parked baseline none of those should reach a real client.
     */
    @Override
    public void send(final Packet<?> packet) {
        // no-op
    }

    @Override
    public void send(final Packet<?> packet, final PacketSendListener listener) {
        // Notify the listener of "sent" so any waiting code can complete,
        // but don't actually write anything to the channel. If a future
        // Paper version expects a specific success/failure protocol we
        // can adjust.
        if (listener != null) {
            try {
                listener.onSuccess();
            } catch (final Throwable ignored) {
                // listener implementations may throw; nothing we can do
            }
        }
    }

    @Override
    public void send(final Packet<?> packet, final PacketSendListener listener, final boolean flush) {
        send(packet, listener);
    }

    /**
     * No-op tick. Connection.tick() handles keepalive and bandwidth
     * accounting on real sockets; for a parked baseline neither matters.
     */
    @Override
    public void tick() {
        // no-op
    }

    /**
     * Discard rather than disconnect. The `disconnect` method on
     * Connection normally closes the channel and broadcasts a
     * disconnection event. For a parked baseline, "disconnect" should be
     * silent because the baseline is not perceived as connected to begin
     * with.
     */
    @Override
    public void disconnect(final net.minecraft.network.chat.Component reason) {
        // no-op
    }

    @Override
    public void handleDisconnection() {
        // no-op
    }

    @Override
    public void flushChannel() {
        // no-op
    }
}
