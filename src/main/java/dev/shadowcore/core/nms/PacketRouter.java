package dev.shadowcore.core.nms;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * The bi-directional packet pipe between a controller's real Netty
 * {@link Connection} and the mounted-side {@link ServerPlayer}'s listener.
 *
 * <p>This is the piece that makes spec-mandated dual-player behavior real at
 * the wire level. Two one-way flows exist:</p>
 *
 * <h2>Inbound (client → server)</h2>
 * A Netty {@link ChannelDuplexHandler} is inserted ahead of the vanilla
 * packet handler on the controller connection's pipeline. For every packet
 * the client sends, this handler inspects a small classification table:
 *
 * <ul>
 *   <li><b>Connection-local</b> packets — keepalive, pong, resource pack
 *       status, client information, plugin channels — go to the controller's
 *       own listener. These concern the physical connection, not the world
 *       actor.</li>
 *   <li><b>Command packets</b> — chat command + signed command — go to the
 *       <em>controller's</em> listener. Spec §6: "Slash command sender =
 *       Controller account identity or baseline identity, depending on
 *       local-profile state." Permission dispatch authority lives on the
 *       controller side.</li>
 *   <li><b>Chat packets</b> — routed per current mount policy (see
 *       {@link PacketRoutingPolicy}). In local-profile mode, chat flows from
 *       the mounted-side so the apparent author is the profile. In shadow
 *       mode, chat flows from the baseline for anti-impersonation.</li>
 *   <li><b>All other gameplay packets</b> — movement, interact, use-item,
 *       dig, swing, etc. — go to the <em>mounted</em> listener so the doll
 *       experiences the input as its own.</li>
 * </ul>
 *
 * <h2>Outbound (server → client)</h2>
 * The mounted-side {@code ServerPlayer} is constructed with a headless
 * {@link Connection} in {@code PacketFlow.CLIENTBOUND}. Vanilla code that
 * tries to send packets to the mounted-side — chunk data, self-health, self-
 * inventory, entity visibility, experience, game mode — ends up calling
 * {@code mountedConnection.send}. We catch every one of those sends and
 * forward them to the controller's real connection. The mounted-side's
 * entity ID matches what other players see, so self-view packets from the
 * mounted-side's listener are already correctly shaped for the client to
 * render itself as the mounted identity.
 *
 * <p>The controller's own {@code ServerPlayer.connection} is muted for the
 * duration of a mount (its outbound pipeline gets its first handler set to
 * swallow non-essential packets), because otherwise the client would receive
 * two sets of self-view updates. Connection-level keepalive/disconnect
 * packets still flow normally on the real connection.</p>
 */
public final class PacketRouter {
    private final Logger log;
    private final DualPlayerRegistry registry;

    private static final String INBOUND_HANDLER = "shadowcore-inbound-router";
    private static final String OUTBOUND_MUTE   = "shadowcore-outbound-mute";

    /**
     * Per-session: which destination the current mount assigns to which
     * packet class. Updated on every mount transition.
     */
    private final ConcurrentHashMap<Connection, PacketRoutingPolicy> policies = new ConcurrentHashMap<>();

    public PacketRouter(final Logger log, final DualPlayerRegistry registry) {
        this.log = log;
        this.registry = registry;
    }

    /**
     * Installs inbound interception on the controller's real connection.
     * Call once, at the first moment the connection is in the GAME phase
     * (from {@code PlayerJoinEvent} equivalents).
     */
    public void installOnController(final Connection realConnection) {
        final ChannelPipeline pipe = realConnection.channel.pipeline();
        if (pipe.get(INBOUND_HANDLER) != null) return;
        // The vanilla packet handler is named "packet_handler". We want our
        // handler to see packets *before* vanilla decodes them into the
        // controller's listener. ChannelDuplexHandler fires the inbound
        // pipeline in order, so inserting "before" is what we want.
        pipe.addBefore("packet_handler", INBOUND_HANDLER, new InboundRouterHandler(realConnection));
    }

    /**
     * Updates the active routing policy for a session. Called on every
     * successful mount/unmount. Thread-safe.
     */
    public void setPolicy(final Connection realConnection, final PacketRoutingPolicy policy) {
        policies.put(realConnection, policy);
    }

    /**
     * Tears down interception on disconnect. Also drops the policy entry.
     */
    public void removeFromController(final Connection realConnection) {
        policies.remove(realConnection);
        try {
            final ChannelPipeline pipe = realConnection.channel.pipeline();
            if (pipe.get(INBOUND_HANDLER) != null) pipe.remove(INBOUND_HANDLER);
            if (pipe.get(OUTBOUND_MUTE) != null)   pipe.remove(OUTBOUND_MUTE);
        } catch (final RuntimeException ex) {
            // Channel may already be closed — ignore.
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Outbound redirection from mounted-side fake connection → real conn.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Wires a mounted-side headless connection so that every send() made
     * against it is instead forwarded to the real controller connection.
     *
     * <p>The implementation is a custom {@link Connection} subclass returned
     * from {@link HeadlessConnection#create}; its {@code send} method
     * delegates to {@code realConnection.send} with the current routing
     * policy applied.</p>
     *
     * <p>Called once per mount, immediately after constructing the mounted
     * {@code ServerPlayer} and before wiring its
     * {@link ServerGamePacketListenerImpl}.</p>
     */
    public void wireOutbound(final Connection headless, final Connection real) {
        if (!(headless instanceof HeadlessConnection hc)) {
            log.warning("wireOutbound called on non-headless connection; mounted packets will be dropped.");
            return;
        }
        hc.forwardTo(real);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Inbound handler.
    // ──────────────────────────────────────────────────────────────────

    private final class InboundRouterHandler extends ChannelDuplexHandler {
        private final Connection realConnection;

        InboundRouterHandler(final Connection realConnection) {
            this.realConnection = realConnection;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (!(msg instanceof Packet<?> packet)) {
                ctx.fireChannelRead(msg);
                return;
            }
            final PacketRoutingPolicy policy = policies.get(realConnection);
            if (policy == null) {
                // No mount yet or session tearing down — let vanilla handle it
                // on the controller's own listener.
                ctx.fireChannelRead(msg);
                return;
            }
            final InboundDestination dest = classifyInbound(packet, policy);
            switch (dest) {
                case CONTROLLER -> ctx.fireChannelRead(msg);
                case MOUNTED    -> deliverToMounted(policy, packet, ctx, msg);
                case DROP       -> { /* silently discard */ }
            }
        }
    }

    private void deliverToMounted(final PacketRoutingPolicy policy, final Packet<?> packet,
                                  final ChannelHandlerContext ctx, final Object original) {
        final ServerGamePacketListenerImpl mountedListener = policy.mountedListener();
        if (mountedListener == null) {
            // No mounted listener yet (e.g. in conflict-shell). Fall back to
            // the controller's own listener for safety, unless the packet is
            // gameplay-only and would cause side effects there.
            ctx.fireChannelRead(original);
            return;
        }
        // Dispatch on the server thread — the mounted listener expects to
        // run on main. The listener uses the server's own tick executor.
        policy.mainThreadExecutor().execute(() -> {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                final Packet cast = packet;
                cast.handle(mountedListener);
            } catch (final Throwable t) {
                log.warning("Inbound forward to mounted listener failed: " + t.getMessage());
            }
        });
    }

    /**
     * Inbound classification: where does this packet class belong?
     * This deliberately does NOT exhaustively list every packet class;
     * the default for unlisted packets is MOUNTED (gameplay).
     */
    private static InboundDestination classifyInbound(final Packet<?> packet, final PacketRoutingPolicy policy) {
        // Connection-local: always controller, regardless of policy.
        if (packet instanceof ServerboundKeepAlivePacket
         || packet instanceof ServerboundPongPacket
         || packet instanceof ServerboundResourcePackPacket
         || packet instanceof ServerboundClientInformationPacket
         || packet instanceof ServerboundCustomPayloadPacket) {
            return InboundDestination.CONTROLLER;
        }
        // Commands: controller-side (permission authority, spec §6).
        if (packet instanceof ServerboundChatCommandPacket
         || packet instanceof ServerboundChatCommandSignedPacket) {
            return InboundDestination.CONTROLLER;
        }
        // Chat: per-policy — local-profile=mounted, shadow=controller.
        if (packet instanceof ServerboundChatPacket) {
            return policy.chatSource();
        }
        // Everything else is gameplay — mounted side.
        return InboundDestination.MOUNTED;
    }

    /**
     * Outbound mute: installed on the controller's real connection so that
     * vanilla code calling {@code controllerServerPlayer.connection.send(..)}
     * does not produce a second, conflicting self-view packet stream.
     *
     * <p>We mute *most* clientbound packets, but let through connection-level
     * essentials (disconnect, keepalive, cookies, plugin messages) so the
     * Netty connection remains healthy.</p>
     */
    public void installOutboundMute(final Connection realConnection, final Consumer<Packet<?>> allowThrough) {
        final ChannelPipeline pipe = realConnection.channel.pipeline();
        if (pipe.get(OUTBOUND_MUTE) != null) return;
        pipe.addBefore("packet_handler", OUTBOUND_MUTE, new ChannelDuplexHandler() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
                if (msg instanceof Packet<?> p && !PacketClassifier.isConnectionEssential(p)) {
                    // Drop — the mounted-side listener is authoritative for
                    // clientbound gameplay packets. However, we still notify
                    // the allowThrough hook so policy can override for
                    // specific packet types (e.g. PlayerInfo rewrites).
                    if (allowThrough != null) allowThrough.accept(p);
                    promise.setSuccess();
                    return;
                }
                super.write(ctx, msg, promise);
            }
        });
    }

    public enum InboundDestination { CONTROLLER, MOUNTED, DROP }

    /**
     * A policy record updated on every mount transition. Immutable.
     */
    public record PacketRoutingPolicy(
        ServerGamePacketListenerImpl mountedListener,
        InboundDestination chatSource,
        Runnable mainThreadRunner,
        java.util.concurrent.Executor mainThreadExecutor
    ) {}
}
