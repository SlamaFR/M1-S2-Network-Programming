package fr.upem.net.udp.packet;

import java.nio.ByteBuffer;

public final class PacketFactory {

    /**
     * Builds an OP packet from the given buffer.
     * <br/>
     * <br/>
     * <b>ATTENTION: The first byte representing the type must
     * have been read!</b>
     *
     * @return {@link PacketOP}
     */
    public static PacketOP createOP(ByteBuffer buffer) {
        if (buffer.remaining() < 4 * Long.BYTES) {
            return null;
        }

        var sessionId = (int) buffer.getLong();
        var isPosOper = (int) buffer.getLong();
        var totalOper = (int) buffer.getLong();
        var opValue = buffer.getLong();

        return new PacketOP(sessionId, isPosOper, totalOper, opValue);
    }

}
