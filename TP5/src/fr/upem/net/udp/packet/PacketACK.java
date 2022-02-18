package fr.upem.net.udp.packet;

import java.nio.ByteBuffer;

public final class PacketACK extends AbstractPacket {

    private final int idPosOper;

    public PacketACK(int sessionId, int idPosOper) {
        super(Packet.ACK, sessionId);
        this.idPosOper = idPosOper;
    }

    @Override
    public void write(ByteBuffer buffer) {
        super.write(buffer);
        buffer.putLong(idPosOper);
        buffer.flip();
    }
}
