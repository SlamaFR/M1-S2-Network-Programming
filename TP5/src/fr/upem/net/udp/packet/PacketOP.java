package fr.upem.net.udp.packet;

import java.nio.ByteBuffer;

public final class PacketOP extends AbstractPacket {

    private final int idPosOper;
    private final int totalOper;
    private final long opValue;

    public PacketOP(int sessionId, int idPosOper, int totalOper, long opValue) {
        super(Packet.OP, sessionId);
        this.idPosOper = idPosOper;
        this.totalOper = totalOper;
        this.opValue = opValue;
    }

    @Override
    public void write(ByteBuffer buffer) {
        super.write(buffer);
        buffer.putLong(idPosOper);
        buffer.putLong(totalOper);
        buffer.putLong(opValue);
        buffer.flip();
    }

    public int getIdPosOper() {
        return idPosOper;
    }

    public int getTotalOper() {
        return totalOper;
    }

    public long getOpValue() {
        return opValue;
    }
}
