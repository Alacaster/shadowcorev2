package dev.shadowcore.core.nms;

import io.netty.channel.ChannelFutureListener;
import java.util.function.BiPredicate;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A headless {@link Connection} for the mounted-side {@link
 * net.minecraft.server.level.ServerPlayer}. It holds no real Netty channel;
 * every {@code send} is forwarded to the controller's real connection.
 *
 * <p>The sole reason this class exists is that vanilla's
 * {@link net.minecraft.server.network.ServerGamePacketListenerImpl}
 * constructor demands a non-null {@link Connection} and uses it for every
 * clientbound dispatch. Giving the listener a {@code new Connection(...)}
 * that points nowhere (as people do when they spawn NPCs) would drop every
 * clientbound packet on the floor — which is fine for an NPC that never
 * needs self-view, but catastrophic for a mounted doll that <em>is</em> the
 * client's view of themselves. So we give it a connection that "points" at
 * the real controller pipe.</p>
 *
 * <p>There is exactly one headless connection per mounted-side ServerPlayer.
 * When the mount swaps, the old one is discarded and a new one is built.</p>
 */
public final class HeadlessConnection extends Connection {
    private static final java.lang.reflect.Field CHANNEL_FIELD = resolveChannelField();
    /** Null until {@link #forwardTo} is called. */
    private Connection realConnection;

    /**
     * A guard that decides whether a given packet should really be forwarded
     * to the real connection. Used by the presentation layer to rewrite or
     * suppress specific packets (PlayerInfo entries, entity metadata skin
     * overlay, etc.) on the way out.
     *
     * <p>Return {@code true} to forward, {@code false} to drop.</p>
     */
    private BiPredicate<Packet<?>, HeadlessConnection> filter = (p, c) -> true;

    private HeadlessConnection(final PacketFlow flow) {
        super(flow);
    }

    /**
     * Constructs a new headless connection in CLIENTBOUND mode. The mounted
     * side's listener sends *clientbound* packets, so the flow orientation
     * is CLIENTBOUND from the mounted listener's point of view.
     */
    public static HeadlessConnection create() {
        return new HeadlessConnection(PacketFlow.CLIENTBOUND);
    }

    public void forwardTo(final Connection real) {
        this.realConnection = real;
        mirrorUnderlyingChannel(real);
    }

    public void setFilter(final BiPredicate<Packet<?>, HeadlessConnection> filter) {
        this.filter = filter == null ? (p, c) -> true : filter;
    }

    public Connection realConnection() { return realConnection; }

    // ──────────────────────────────────────────────────────────────────
    //  Override every send path in Connection.
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void send(final Packet<?> packet) {
        send(packet, (ChannelFutureListener) null, true);
    }

    @Override
    public void send(final Packet<?> packet, final ChannelFutureListener listener) {
        send(packet, listener, true);
    }

    @Override
    public void send(final Packet<?> packet, final ChannelFutureListener listener, final boolean flush) {
        if (realConnection == null) return;        // mount not yet wired
        if (!filter.test(packet, this)) return;    // presentation policy drop
        realConnection.send(packet, listener, flush);
    }

    @Override
    public void flushChannel() {
        if (realConnection == null) return;
        realConnection.flushChannel();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Disable lifecycle methods that vanilla might call on the fake conn.
    //  We do NOT want disconnecting the mounted-side to actually close the
    //  real controller connection.
    // ──────────────────────────────────────────────────────────────────

    @Override
    public boolean isConnected() {
        return realConnection != null && realConnection.isConnected();
    }

    public boolean isConnecting() {
        return false;
    }

    @Override
    public void disconnect(final net.minecraft.network.chat.Component reason) {
        // Swallow — closing the mounted doll must NOT close the controller's
        // real connection. The mounted-side's lifecycle is managed by
        // MountKernel via PlayerList.remove.
    }

    @Override
    public void handleDisconnection() {
        // Same rationale.
    }

    private static java.lang.reflect.Field resolveChannelField() {
        try {
            final java.lang.reflect.Field f = Connection.class.getDeclaredField("channel");
            f.setAccessible(true);
            return f;
        } catch (final ReflectiveOperationException ex) {
            return null;
        }
    }

    private void mirrorUnderlyingChannel(final Connection real) {
        if (CHANNEL_FIELD == null || real == null) return;
        try {
            final Object delegateChannel = CHANNEL_FIELD.get(real);
            CHANNEL_FIELD.set(this, delegateChannel);
        } catch (final ReflectiveOperationException ignored) {
            // Best-effort only; send/flushChannel delegation still works for most paths.
        }
    }
}
