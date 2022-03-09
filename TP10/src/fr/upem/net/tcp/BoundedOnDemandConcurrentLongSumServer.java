package fr.upem.net.tcp;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BoundedOnDemandConcurrentLongSumServer {

    private static final Logger logger = Logger.getLogger(BoundedOnDemandConcurrentLongSumServer.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final Semaphore semaphore;
    private final ServerSocketChannel serverSocketChannel;

    public BoundedOnDemandConcurrentLongSumServer(int port, int limit) throws IOException {
        semaphore = new Semaphore(limit);
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        logger.info(this.getClass().getName() + " starts on port " + port + " with " + limit + " clients max");
    }

    /**
     * Iterative server main loop
     *
     * @throws IOException
     */
    public void launch() throws IOException, InterruptedException {
        logger.info("Server started");
        while (!Thread.interrupted()) {
            semaphore.acquire();
            SocketChannel client = serverSocketChannel.accept();
            var thread = new Thread(() -> {
                try {
                    logger.info("Connection accepted from " + client.getRemoteAddress());
                    serve(client);
                } catch (IOException ioe) {
                    logger.log(Level.SEVERE, "Connection terminated with client by IOException", ioe.getCause());
                } finally {
                    silentlyClose(client);
                    semaphore.release();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Treat the connection sc applying the protocol. All IOException are thrown
     *
     * @param sc
     * @throws IOException
     */
    private void serve(SocketChannel sc) throws IOException {
        while (sc.isOpen()) {
            var preamble = ByteBuffer.allocate(Integer.BYTES);
            if (!readFully(sc, preamble)) {
                return;
            }
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
        }
    }

    /**
     * Close a SocketChannel while ignoring IOExecption
     *
     * @param sc
     */

    private void silentlyClose(Closeable sc) {
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

    public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
        var port = Integer.parseInt(args[0]);
        var limit = Integer.parseInt(args[1]);
        var server = new BoundedOnDemandConcurrentLongSumServer(port, limit);
        server.launch();
    }
}