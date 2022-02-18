package fr.upem.net.udp.packet;

import java.nio.ByteBuffer;

public sealed interface Packet permits AbstractPacket {

    byte OP = 1;
    byte ACK = 2;
    byte RES = 3;

    /**
     * Writes down the packet into the given buffer.
     * The buffer is then flipped, ready to be sent.
     */
    void write(ByteBuffer buffer);

}
