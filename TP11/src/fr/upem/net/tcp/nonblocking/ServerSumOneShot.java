package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.logging.Logger;

public class ServerSumOneShot {

    private static final int BUFFER_SIZE = 2 * Integer.BYTES;
    private static final Logger logger = Logger.getLogger(ServerSumOneShot.class.getName());

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;

    public ServerSumOneShot(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        selector = Selector.open();
    }

    public void launch() throws IOException {
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        while (!Thread.interrupted()) {
            try {
                Helpers.printKeys(selector); // for debug
                System.out.println("Starting select");
                selector.select(this::treatKey);
                System.out.println("Select finished");
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
        }
    }

    private void treatKey(SelectionKey key) {
        try {
            Helpers.printSelectedKey(key); // for debug
            if (key.isValid() && key.isAcceptable()) {
                doAccept(key);
            }
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

    private void doAccept(SelectionKey key) throws IOException {
        var ssc = (ServerSocketChannel) key.channel();
        var sc = ssc.accept();
        if (sc == null) {
            logger.severe("Selected lied");
            return;
        }
        var buffer = ByteBuffer.allocate(BUFFER_SIZE);
        sc.configureBlocking(false);
        sc.register(selector, SelectionKey.OP_READ, buffer);
    }

    private void doRead(SelectionKey key) throws IOException {
        var sc = (SocketChannel) key.channel();
        var buffer = (ByteBuffer) key.attachment();

        var read = sc.read(buffer);
        if (read == 0) {
            logger.severe("Selector lied");
            return;
        }
        if (!buffer.hasRemaining()) {
            buffer.flip();
            var a = buffer.getInt();
            var b = buffer.getInt();
            buffer.clear().putInt(a + b).flip();
            key.interestOps(SelectionKey.OP_WRITE);
        } else if (read == -1) {
            logger.info("Connection lost with " + sc.getRemoteAddress());
            silentlyClose(key);
        }
    }

    private void doWrite(SelectionKey key) throws IOException {
        var sc = (SocketChannel) key.channel();
        var buffer = (ByteBuffer) key.attachment();

        sc.write(buffer);
        if (!buffer.hasRemaining()) {
            buffer.clear();
            silentlyClose(key);
        }
    }

    private void silentlyClose(SelectionKey key) {
        var sc = (Channel) key.channel();
        try {
            sc.close();
        } catch (IOException e) {
            // ignore exception
        }
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 1) {
            usage();
            return;
        }
        new ServerSumOneShot(Integer.parseInt(args[0])).launch();
    }

    private static void usage() {
        System.out.println("Usage : ServerSumOneShot port");
    }
}