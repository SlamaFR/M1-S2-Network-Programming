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
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerEchoWithConsole {
    static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        private boolean closed = false;

        private Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        /**
         * Update the interestOps of the key looking only at values of the boolean
         * closed and the ByteBuffer buffer.
         * <p>
         * The convention is that buff is in write-mode.
         */
        private void updateInterestOps() {
            if (closed || !buffer.hasRemaining()) {
                key.interestOps(SelectionKey.OP_WRITE);
            } else if (buffer.position() == 0) {
                key.interestOps(SelectionKey.OP_READ);
            } else {
                key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            }
        }

        /**
         * Performs the read action on sc
         * <p>
         * The convention is that buffer is in write-mode before calling doRead and is in
         * write-mode after calling doRead
         *
         * @throws IOException .
         */
        private void doRead() throws IOException {
            var bytes = sc.read(buffer);
            if (bytes == 0) {
                logger.severe("Selector lied on read");
            }
            if (bytes == -1) {
                logger.info("Connection closed by " + sc.getRemoteAddress());
                closed = true;
            }
            updateInterestOps();
        }

        /**
         * Performs the write action on sc
         * <p>
         * The convention is that buffer is in write-mode before calling doWrite and is in
         * write-mode after calling doWrite
         *
         * @throws IOException .
         */
        private void doWrite() throws IOException {
            buffer.flip();
            if (closed && !buffer.hasRemaining()) {
                silentlyClose();
                return;
            }
            var bytes = sc.write(buffer);
            buffer.compact();
            if (bytes == 0) {
                logger.severe("Selector lied on write");
            }
            updateInterestOps();
        }

        private void silentlyClose() {
            try {
                sc.close();
            } catch (IOException e) {
                // ignore exception
            }
        }
    }

    private static final int BUFFER_SIZE = 1_024;
    private static final Logger logger = Logger.getLogger(ServerEchoWithConsole.class.getName());

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;
    private final Thread console;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);

    public ServerEchoWithConsole(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        selector = Selector.open();
        console = new Thread(this::consoleRun);
    }

    private void consoleRun() {
        try {
            try (var scanner = new Scanner(System.in)) {
                while (!Thread.interrupted() && scanner.hasNextLine()) {
                    var msg = scanner.nextLine();
                    sendCommand(msg);
                }
            }
            logger.info("Console thread stopping");
        } catch (InterruptedException e) {
            logger.info("Console thread interrupted");
        }
    }

    /**
     * Send instructions to the selector via a BlockingQueue and wake it up
     */
    private void sendCommand(String cmd) throws InterruptedException {
        queue.put(cmd);
        if (!queue.isEmpty()) {
            selector.wakeup();
        }
    }

    /**
     * Processes the command from the BlockingQueue
     */
    private void processCommands() throws IOException {
        while (!queue.isEmpty()) {
            switch (queue.poll().toUpperCase()) {
                case "INFO" -> {
                    var connected = selector.keys().stream()
                            .filter(k -> !k.channel().equals(serverSocketChannel))
                            .filter(k -> !((Context) k.attachment()).closed)
                            .count();
                    logger.info("Clients connected: " + connected);
                }
                case "SHUTDOWN" -> {
                    logger.info("Shutting down...");
                    try {
                        serverSocketChannel.close();
                    } catch (IOException e) {
                        // Do nothing
                    }
                }
                case "SHUTDOWNNOW" -> {
                    logger.info("Shutting down now...");
                    selector.keys().forEach(this::silentlyClose);
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void launch() throws IOException {
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        console.setDaemon(true);
        console.start();
        while (!Thread.interrupted() && serverSocketChannel.isOpen()) {
            Helpers.printKeys(selector); // for debug
            System.out.println("Starting select");
            try {
                selector.select(this::treatKey);
                processCommands();
            } catch (UncheckedIOException tunneled) {
                throw tunneled.getCause();
            }
            System.out.println("Select finished");
        }
        console.interrupt();
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
        k.attach(new Context(k));
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
        new ServerEchoWithConsole(Integer.parseInt(args[0])).launch();
    }

    private static void usage() {
        System.out.println("Usage : ServerEcho port");
    }
}