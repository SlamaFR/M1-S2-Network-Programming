package fr.upem.net.tcp;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FixedPrestartedConcurrentLongSumServerWithTimeout {

    private static final Logger logger = Logger.getLogger(FixedPrestartedConcurrentLongSumServerWithTimeout.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final ServerSocketChannel serverSocketChannel;

    public FixedPrestartedConcurrentLongSumServerWithTimeout(int port) throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        logger.info(this.getClass().getName() + " starts on port " + port);
    }

    /**
     * Iterative server main loop
     *
     * @throws IOException
     */
    public void launch(ThreadData data) throws IOException {
        logger.info("Server started");
        while (!Thread.interrupted()) {
            SocketChannel client = serverSocketChannel.accept();
            var address = client.getRemoteAddress();
            data.setSocketChannel(client);
            try {
                logger.info("Connection accepted from " + address);
                serve(client, data);
            } catch (ClosedByInterruptException e) {
                logger.info("Connection with " + address + " closed");
            } catch (AsynchronousCloseException e) {
                logger.info("Connection with " + address + " closed due to inactivity");
            } catch (IOException ioe) {
                logger.warning("Connection terminated by " + address);
            } finally {
                silentlyClose(client);
            }
        }
    }

    /**
     * Treat the connection sc applying the protocol. All IOException are thrown
     *
     * @param sc
     * @throws IOException
     */
    private void serve(SocketChannel sc, ThreadData data) throws IOException {
        while (sc.isOpen()) {
            var preamble = ByteBuffer.allocate(Integer.BYTES);
            if (!readFully(sc, preamble)) {
                return;
            }
            data.tick();
            preamble.flip();

            if (preamble.remaining() < Integer.BYTES) {
                logger.warning("Malformed packet");
                return;
            }

            long sum = 0;
            var operations = preamble.getInt();

            var buffer = ByteBuffer.allocate(Math.min(operations * Long.BYTES, BUFFER_SIZE));
            buffer.flip();

            for (int i = 0; i < operations; i++) {
                if (!buffer.hasRemaining()) {
                    buffer.clear();
                    if (!readFully(sc, buffer)) {
                        return;
                    }
                    data.tick();
                    buffer.flip();
                }
                if (buffer.remaining() < Long.BYTES) {
                    logger.warning("Malformed packet");
                    buffer.clear();
                }
                sum += buffer.getLong();
            }

            buffer.clear();
            buffer.putLong(sum);
            sc.write(buffer.flip());
            data.tick();
        }
    }

    /**
     * Close a SocketChannel while ignoring IOExecption
     *
     * @param sc
     */
    static void silentlyClose(Closeable sc) {
        if (sc != null) {
            try {
                sc.close();
            } catch (IOException e) {
                // Do nothing
            }
        }
    }

    static boolean readFully(SocketChannel sc, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (sc.read(buffer) == -1) {
                logger.info("Input stream closed");
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws NumberFormatException, IOException {
        var server = new FixedPrestartedConcurrentLongSumServerWithTimeout(Integer.parseInt(args[0]));
        var threads = new ArrayList<Thread>();
        var threadDatas = new ArrayList<ThreadData>();
        var timeout = Integer.parseInt(args[2]);

        for (int i = 0; i < Integer.parseInt(args[1]); i++) {
            var thread = new Thread(() -> {
                final ThreadData data = new ThreadData();
                threadDatas.add(data);
                try {
                    server.launch(data);
                } catch (AsynchronousCloseException e) {
                    logger.info("Worker " + Thread.currentThread().getName() + " terminated");
                } catch (IOException ioe) {
                    logger.log(Level.SEVERE, "Worker " + Thread.currentThread().getName() + " terminated unexpectedly", ioe.getCause());
                    silentlyClose(server.serverSocketChannel);
                    return;
                }
            });
            threads.add(thread);
            thread.start();
        }
        var manager = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(timeout);
                    for (var data : threadDatas) {
                        data.closeIfInactive(timeout);
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        });
        manager.setDaemon(true);
        manager.start();

        try (var scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                switch (scanner.nextLine()) {
                    case "INFO" -> {
                        System.out.println("Client connected:");
                        System.out.println(threadDatas.stream().filter(ThreadData::connected).count());
                    }
                    case "SHUTDOWN" -> {
                        silentlyClose(server.serverSocketChannel);
                        return;
                    }
                    case "SHUTDOWNNOW" -> {
                        threads.forEach(Thread::interrupt);
                        return;
                    }
                    default -> System.out.println("Unknown command");
                }
            }
        }
    }

    static class ThreadData {

        private final Object lock = new Object();
        private SocketChannel channel;
        private long lastTick = 0;

        boolean connected() {
            synchronized (lock) {
                return channel != null;
            }
        }

        void setSocketChannel(SocketChannel channel) {
            synchronized (lock) {
                this.channel = channel;
                this.lastTick = System.currentTimeMillis();
            }
        }

        void tick() {
            synchronized (lock) {
                this.lastTick = System.currentTimeMillis();
            }
        }

        void closeIfInactive(int timeout) {
            synchronized (lock) {
                if (System.currentTimeMillis() - lastTick >= timeout) {
                    close();
                }
            }
        }

        void close() {
            synchronized (lock) {
                if (this.channel != null) {
                    silentlyClose(this.channel);
                }
            }
        }

    }
}