package fr.upem.net.udp.packet;

import java.nio.ByteBuffer;

public final class PacketRES extends AbstractPacket {

    private final long sum;

    public PacketRES(int sessionId, long sum) {
        super(Packet.RES, sessionId);
        this.sum = sum;
    }

    @Override
    public void write(ByteBuffer buffer) {
        super.write(buffer);
        buffer.putLong(sum);
        buffer.flip();
    }
}
