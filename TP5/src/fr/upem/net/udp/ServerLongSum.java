package fr.upem.net.udp;

import fr.upem.net.udp.packet.Packet;
import fr.upem.net.udp.packet.PacketACK;
import fr.upem.net.udp.packet.PacketFactory;
import fr.upem.net.udp.packet.PacketOP;
import fr.upem.net.udp.packet.PacketRES;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Objects;
import java.util.logging.Logger;

public class ServerLongSum {

    private static final Logger logger = Logger.getLogger(ServerLongSum.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final DatagramChannel dc;
    private final HashMap<SocketAddress, HashMap<Integer, Session>> clientSessions;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

    public ServerLongSum(int port) throws IOException {
        dc = DatagramChannel.open();
        dc.bind(new InetSocketAddress(port));
        logger.info("ServerLongSum started on port " + port);
        this.clientSessions = new HashMap<>();
    }

    private void acknowledge(SocketAddress client, PacketOP op) throws IOException {
        logger.info("Acknowledging " + client);
        var packet = new PacketACK(op.getSessionId(), op.getIdPosOper());
        buffer.clear();
        packet.write(buffer);
        dc.send(buffer, client);
    }

    private void result(SocketAddress client, Session session) throws IOException {
        logger.info("Sending result of session " + session.sessionId + " to " + client);
        var packet = new PacketRES(session.sessionId, session.sum);
        buffer.clear();
        packet.write(buffer);
        dc.send(buffer, client);
    }

    private void handleOp(ByteBuffer buffer, SocketAddress client) throws IOException {
        var packet = PacketFactory.createOP(buffer);
        if (packet == null) {
            logger.severe("Received malformed OP packet");
            return;
        }

        var sessionId = packet.getSessionId();
        var sessions = clientSessions.get(client);

        if (!sessions.containsKey(sessionId)) {
            sessions.put(sessionId, new Session(sessionId, packet.getTotalOper()));
        }

        logger.info("Received " + packet.getIdPosOper() + "/" + packet.getTotalOper() + " on session " + packet.getSessionId() + " from " + client);

        var session = sessions.get(sessionId);
        var completed = session.op(packet);
        acknowledge(client, packet);
        if (completed) {
            result(client, session);
        }
    }

    public void serve() throws IOException {
        try {
            while (!Thread.interrupted()) {
                buffer.clear();

                var client = dc.receive(buffer);
                buffer.flip();
                if (buffer.remaining() < Long.BYTES) {
                    logger.severe("Received malformed packet");
                    continue;
                }

                if (!clientSessions.containsKey(client)) {
                    logger.info("First time receiving packet from client " + client);
                    clientSessions.put(client, new HashMap<>());
                }

                switch (buffer.get()) {
                    case Packet.OP -> handleOp(buffer, client);
                    case Packet.ACK -> logger.warning("Received ACK packet");
                    case Packet.RES -> logger.warning("Received RES packet");
                    default -> logger.severe("Unrecognized received packet type");
                }
            }
        } finally {
            dc.close();
        }
    }

    public static void usage() {
        System.err.println("Usage : ServerLongSum port");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            usage();
            System.exit(1);
        }

        var port = Integer.parseInt(args[0]);

        if (!(port >= 1024) & port <= 65535) {
            logger.severe("The port number must be between 1024 and 65535");
            System.exit(2);
        }

        try {
            new ServerLongSum(port).serve();
        } catch (BindException e) {
            logger.severe("Server could not bind on " + port + "\nAnother server is probably running on this port.");
            System.exit(3);
        }
    }

    public static class Session {

        private final int sessionId;
        private final int totalOper;
        private final BitSet bitSet;

        private long sum = 0L;

        public Session(int sessionId, int totalOper) {
            this.sessionId = sessionId;
            this.totalOper = totalOper;
            this.bitSet = new BitSet(totalOper);
            this.bitSet.flip(0, totalOper);
        }

        public boolean op(PacketOP op) {
            Objects.requireNonNull(op);
            if (!bitSet.get(op.getIdPosOper())) {
                return bitSet.cardinality() == 0;
            }

            if (op.getIdPosOper() < 0 || op.getIdPosOper() >= op.getTotalOper()) {
                logger.severe("IdPos should be between 0 and TotalOper");
                return bitSet.cardinality() == 0;
            }
            if (op.getTotalOper() != totalOper) {
                logger.severe("TotalOper differs from saved one");
                return bitSet.cardinality() == 0;
            }

            bitSet.set(op.getIdPosOper(), false);
            sum += op.getOpValue();

            return bitSet.cardinality() == 0;
        }
    }
}