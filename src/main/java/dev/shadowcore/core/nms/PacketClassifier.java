package dev.shadowcore.core.nms;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;

/**
 * Classification helpers for routing decisions.
 *
 * <p>A packet is "connection-essential" when dropping it on the controller's
 * real connection would damage the Netty-level session. These packets must
 * flow through even while the mounted-side is the authoritative source of
 * clientbound traffic — we never want to silently swallow a keepalive.</p>
 */
public final class PacketClassifier {
    private PacketClassifier() {}

    public static boolean isConnectionEssential(final Packet<?> packet) {
        return packet instanceof ClientboundKeepAlivePacket
            || packet instanceof ClientboundPingPacket
            || packet instanceof ClientboundDisconnectPacket
            || packet instanceof ClientboundResourcePackPushPacket
            || packet instanceof ClientboundResourcePackPopPacket
            || packet instanceof ClientboundStoreCookiePacket
            || packet instanceof ClientboundTransferPacket
            || packet instanceof ClientboundCustomPayloadPacket;
    }
}
