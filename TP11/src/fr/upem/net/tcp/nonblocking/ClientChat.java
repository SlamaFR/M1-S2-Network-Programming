package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ClientChat {

    static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private final ArrayDeque<Message> queue = new ArrayDeque<>();
        private final MessageReader reader = new MessageReader();
        private boolean closed = false;

        private Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        /**
         * Process the content of bufferIn
         * <p>
         * The convention is that bufferIn is in write-mode before the call to process
         * and after the call
         */
        private void processIn() {
            while (bufferIn.hasRemaining()) {
                switch (reader.process(bufferIn)) {
                    case DONE -> {
                        var message = reader.get();
                        reader.reset();
                        System.out.println(message);
                    }
                    case ERROR -> {
                        logger.severe("Malformed message");
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
         */
        private void queueMessage(Message msg) throws InterruptedException {
            queue.offer(msg);
            processOut();
            updateInterestOps();
        }

        /**
         * Try to fill bufferOut from the message queue
         */
        private void processOut() throws InterruptedException {
            while (!queue.isEmpty()) {
                var message = queue.peek();
                var messageBuffer = message.toByteBuffer();

                if (bufferOut.remaining() >= messageBuffer.remaining()) {
                    bufferOut.put(messageBuffer);
                    queue.pop();
                } else {
                    return;
                }
            }
        }

        /**
         * Update the interestOps of the key looking only at values of the boolean
         * closed and of both ByteBuffers.
         * <p>
         * The convention is that both buffers are in write-mode before the call to
         * updateInterestOps and after the call. Also, it is assumed that process has
         * been called just before updateInterestOps.
         */
        private void updateInterestOps() {
            var interestOps = 0;
            if (!closed && bufferIn.hasRemaining()) {
                interestOps |= SelectionKey.OP_READ;
            }
            if (!closed && bufferOut.position() != 0) {
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
                logger.severe("Selector gave a bad hint");
                return;
            }
            if (bytes == -1) {
                logger.info("Connection closed by peer");
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
        private void doWrite() throws IOException, InterruptedException {
            bufferOut.flip();
            if (closed && !bufferIn.hasRemaining()) {
                silentlyClose();
                return;
            }
            var bytes = sc.write(bufferOut);
            bufferOut.compact();
            if (bytes == 0) {
                logger.severe("Selector gave a bad hint");
            }
            processOut();
            updateInterestOps();
        }

        public void doConnect() throws IOException {
            if (!sc.finishConnect()) {
                return;
            }
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    static private int BUFFER_SIZE = 10_000;
    static private Logger logger = Logger.getLogger(ClientChat.class.getName());

    private final SocketChannel sc;
    private final Selector selector;
    private final InetSocketAddress serverAddress;
    private final String login;
    private final Thread console;
    private final BlockingQueue<Message> queue = new ArrayBlockingQueue<>(10);
    private Context uniqueContext;

    public ClientChat(String login, InetSocketAddress serverAddress) throws IOException {
        this.serverAddress = serverAddress;
        this.login = login;
        this.sc = SocketChannel.open();
        this.selector = Selector.open();
        this.console = new Thread(this::consoleRun);
    }

    private void consoleRun() {
        try (var scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                var msg = scanner.nextLine();
                sendCommand(msg);
            }
        }
        logger.info("Console thread stopping");
    }

    /**
     * Send instructions to the selector via a BlockingQueue and wake it up
     *
     * @param msg
     * @throws InterruptedException
     */
    private void sendCommand(String msg) {
        try {
            var msgToSend = new Message(login, msg);
            queue.put(msgToSend);
            if (!queue.isEmpty()) {
                selector.wakeup();
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Processes the command from the BlockingQueue
     */
    private void processCommands() {
        try {
            while (!queue.isEmpty()) {
                var msg = queue.poll();
                uniqueContext.queueMessage(msg);
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    public void launch() throws IOException {
        sc.configureBlocking(false);
        var key = sc.register(selector, SelectionKey.OP_CONNECT);
        uniqueContext = new Context(key);
        key.attach(uniqueContext);
        sc.connect(serverAddress);

        console.start();

        while (!Thread.interrupted()) {
            try {
                selector.select(k -> {
                    try {
                        treatKey(k);
                    } catch (InterruptedException e) {
                        logger.info("Interrupted while treating key");
                    }
                });
                processCommands();
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
        }
    }

    private void treatKey(SelectionKey key) throws InterruptedException {
        try {
            if (key.isValid() && key.isConnectable()) {
                uniqueContext.doConnect();
            }
            if (key.isValid() && key.isWritable()) {
                uniqueContext.doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                uniqueContext.doRead();
            }
        } catch (IOException ioe) {
            // lambda call in select requires to tunnel IOException
            throw new UncheckedIOException(ioe);
        }
    }

    private void silentlyClose(SelectionKey key) {
        Channel sc = key.channel();
        try {
            sc.close();
        } catch (IOException e) {
            // ignore exception
        }
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        if (args.length != 3) {
            usage();
            return;
        }
        new ClientChat(args[0], new InetSocketAddress(args[1], Integer.parseInt(args[2]))).launch();
    }

    private static void usage() {
        System.out.println("Usage : ClientChat login hostname port");
    }
}