package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ServerChaton {
    static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final MessageReader messageReader = new MessageReader();
        private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
        private final ServerChaton server; // we could also have Context as an instance class, which would naturally
        // give access to ServerChatInt.this
        private boolean closed = false;

        private Context(ServerChaton server, SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
            this.server = server;
        }

        /**
         * Process the content of bufferIn
         * <p>
         * The convention is that bufferIn is in write-mode before the call to process and
         * after the call
         */
        private void processIn() {
            while (bufferIn.hasRemaining()) {
                switch (messageReader.process(bufferIn)) {
                    case DONE -> {
                        server.broadcast(messageReader.get());
                        messageReader.reset();
                    }
                    case ERROR -> {
                        logger.severe("Malformed message packet");
                        silentlyClose();
                        return;
                    }
                    case REFILL -> {
                        return;
                    }
                }
            }
        }

        /**
         * Add a message to the message queue, tries to fill bufferOut and updateInterestOps
         *
         * @param msg
         */
        public void queueMessage(Message msg) {
            var nickname = UTF_8.encode(msg.nickname());
            var content = UTF_8.encode(msg.message());

            var buffer = ByteBuffer.allocate(2 * Integer.BYTES + nickname.remaining() + content.remaining());
            buffer.putInt(nickname.remaining());
            buffer.put(nickname);
            buffer.putInt(content.remaining());
            buffer.put(content);
            buffer.flip();

            queue.offer(buffer);
            processOut();
            updateInterestOps();
        }

        /**
         * Try to fill bufferOut from the message queue
         */
        private void processOut() {
            while (bufferOut.hasRemaining() && !queue.isEmpty()) {
                var msg = queue.peek();
                if (!msg.hasRemaining()) {
                    queue.pop();
                    continue;
                }
                if (bufferOut.remaining() >= msg.remaining()) {
                    bufferOut.put(msg);
                } else {
                    var oldLimit = msg.limit();
                    msg.limit(bufferOut.remaining());
                    bufferOut.put(msg);
                    msg.limit(oldLimit);
                }
            }
        }

        /**
         * Update the interestOps of the key looking only at values of the boolean
         * closed and of both ByteBuffers.
         * <p>
         * The convention is that both buffers are in write-mode before the call to
         * updateInterestOps and after the call. Also it is assumed that process has
         * been be called just before updateInterestOps.
         */
        private void updateInterestOps() {
            var interestOps = 0;
            if (!closed && bufferIn.hasRemaining()) {
                interestOps |= SelectionKey.OP_READ;
            }
            if (bufferOut.position() != 0) {
                interestOps |= SelectionKey.OP_WRITE;
            }
            if (interestOps == 0) {
                silentlyClose();
                return;
            }
            key.interestOps(interestOps);
        }

        private void silentlyClose() {
            try {
                sc.close();
            } catch (IOException e) {
                // ignore exception
            }
        }

        /**
         * Performs the read action on sc
         * <p>
         * The convention is that both buffers are in write-mode before the call to
         * doRead and after the call
         *
         * @throws IOException
         */
        private void doRead() throws IOException {
            var bytes = sc.read(bufferIn);
            if (bytes == 0) {
                logger.severe("Selector lied on read");
                return;
            }
            if (bytes == -1) {
                closed = true;
            }
            processIn();
            updateInterestOps();
        }

        /**
         * Performs the write action on sc
         * <p>
         * The convention is that both buffers are in write-mode before the call to
         * doWrite and after the call
         *
         * @throws IOException
         */
        private void doWrite() throws IOException {
            bufferOut.flip();
            if (closed && !bufferOut.hasRemaining()) {
                silentlyClose();
                return;
            }
            var bytes = sc.write(bufferOut);
            bufferOut.compact();
            if (bytes == 0) {
                logger.severe("Selector lied on write");
            }
            processOut();
            updateInterestOps();
        }

    }

    private static final int BUFFER_SIZE = 1_024;
    private static final Logger logger = Logger.getLogger(ServerChaton.class.getName());

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;

    public ServerChaton(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        selector = Selector.open();
    }

    public void launch() throws IOException {
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        while (!Thread.interrupted()) {
            Helpers.printKeys(selector); // for debug
            System.out.println("Starting select");
            try {
                selector.select(this::treatKey);
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
            System.out.println("Select finished");
        }
    }

    private void treatKey(SelectionKey key) {
        Helpers.printSelectedKey(key); // for debug
        try {
            if (key.isValid() && key.isAcceptable()) {
                doAccept(key);
            }
        } catch (IOException ioe) {
            // lambda call in select requires to tunnel IOException
            throw new UncheckedIOException(ioe);
        }
        try {
            if (key.isValid() && key.isWritable()) {
                ((Context) key.attachment()).doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                ((Context) key.attachment()).doRead();
            }
        } catch (IOException e) {
            logger.log(Level.INFO, "Connection closed with client due to IOException", e);
            silentlyClose(key);
        }
    }

    private void doAccept(SelectionKey key) throws IOException {
        var ssc = (ServerSocketChannel) key.channel();
        var sc = ssc.accept();
        if (sc == null) {
            logger.severe("Selector lied");
            return;
        }
        sc.configureBlocking(false);
        var k = sc.register(selector, SelectionKey.OP_READ);
        k.attach(new Context(this, k));
    }

    private void silentlyClose(SelectionKey key) {
        var sc = key.channel();
        try {
            sc.close();
        } catch (IOException e) {
            // ignore exception
        }
    }

    /**
     * Add a message to all connected clients queue
     *
     * @param msg
     */
    private void broadcast(Message msg) {
        for (var key : selector.keys()) {
            if (key.channel() != serverSocketChannel) {
                ((Context) key.attachment()).queueMessage(msg);
            }
        }
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 1) {
            usage();
            return;
        }
        new ServerChaton(Integer.parseInt(args[0])).launch();
    }

    private static void usage() {
        System.out.println("Usage : ServerSumBetter port");
    }
}