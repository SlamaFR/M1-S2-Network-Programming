package fr.upem.net.udp.packet;

import java.nio.ByteBuffer;

abstract sealed class AbstractPacket implements Packet permits PacketOP, PacketACK, PacketRES {

    private final byte type;
    private final int sessionId;

    public AbstractPacket(byte type, int sessionId) {
        this.type = type;
        this.sessionId = sessionId;
    }

    @Override
    public void write(ByteBuffer buffer) {
        buffer.clear();
        buffer.put(type);
        buffer.putLong(sessionId);
    }

    public byte getType() {
        return type;
    }

    public int getSessionId() {
        return sessionId;
    }
}
