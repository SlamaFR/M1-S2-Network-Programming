package fr.upem.net.udp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.logging.Logger;

public class ServerEchoMultiPort {
    private static final Logger logger = Logger.getLogger(ServerEchoMultiPort.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final Selector selector;
    private final int startPort;
    private final int endPort;

    public ServerEchoMultiPort(int startPort, int endPort) throws IOException {
        this.startPort = startPort;
        this.endPort = endPort;
        this.selector = Selector.open();

        for (int port = startPort; port < endPort; port++) {
            var dc = DatagramChannel.open();
            dc.bind(new InetSocketAddress(port));
            dc.configureBlocking(false);
            dc.register(selector, SelectionKey.OP_READ, new Context());
        }
    }

    public void serve() throws IOException {
        logger.info("ServerEcho started on port range " + startPort + " to " + endPort);
        while (!Thread.interrupted()) {
            try {
                selector.select(this::treatKey);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isWritable()) {
                doWrite(key);
            }
            if (key.isValid() && key.isReadable()) {
                doRead(key);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    private void doRead(SelectionKey key) throws IOException {
        var context = (Context) key.attachment();
        var dc = (DatagramChannel) key.channel();
        var buffer = context.buffer;
        context.client = dc.receive(buffer);
        if (context.client == null) {
            return;
        }
        logger.info("Packet received from " + context.client);
        key.interestOps(SelectionKey.OP_WRITE);
        buffer.flip();
    }

    private void doWrite(SelectionKey key) throws IOException {
        var context = (Context) key.attachment();
        var dc = (DatagramChannel) key.channel();
        var buffer = context.buffer;
        if (context.client != null) {
            dc.send(buffer, context.client);
            if (!buffer.hasRemaining()) {
                logger.info("Packet sent to " + context.client);
                key.interestOps(SelectionKey.OP_READ);
                context.client = null;
                buffer.clear();
            }
        }
    }

    public static void usage() {
        System.out.println("Usage : ServerEcho startPort endPort");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            usage();
            return;
        }
        new ServerEchoMultiPort(Integer.parseInt(args[0]), Integer.parseInt(args[1])).serve();
    }

    public static final class Context {

        private SocketAddress client;
        private final ByteBuffer buffer;

        public Context() {
            this.buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        }
    }
}
