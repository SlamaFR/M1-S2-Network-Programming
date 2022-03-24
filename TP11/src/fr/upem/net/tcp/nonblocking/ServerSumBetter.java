package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerSumBetter {
    static private class Context {
        private final SelectionKey key;
        private final SocketChannel sc;
        private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
        private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);
        private boolean closed = false;

        private Context(SelectionKey key) {
            this.key = key;
            this.sc = (SocketChannel) key.channel();
        }

        /**
         * Process the content of bufferIn into bufferOut
         * <p>
         * The convention is that both buffers are in write-mode before the call to
         * process and after the call
         */

        private void process() {
            bufferIn.flip();
			while (bufferIn.remaining() >= 2 * Integer.BYTES && bufferOut.remaining() >= Integer.BYTES) {
				var a = bufferIn.getInt();
				var b = bufferIn.getInt();
				bufferOut.putInt(a + b);
            }
            bufferIn.compact();
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
            }
            if (bytes == -1) {
                logger.info("Connection closed by " + sc.getRemoteAddress());
                closed = true;
            }
			process();
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
			process();
            updateInterestOps();
        }

    }

    private static final int BUFFER_SIZE = 1024;
    private static final Logger logger = Logger.getLogger(ServerSumBetter.class.getName());

    private final ServerSocketChannel serverSocketChannel;
    private final Selector selector;

    public ServerSumBetter(int port) throws IOException {
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
        k.attach(new Context(k));
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
        if (args.length != 1) {
            usage();
            return;
        }
        new ServerSumBetter(Integer.parseInt(args[0])).launch();
    }

    private static void usage() {
        System.out.println("Usage : ServerSumBetter port");
    }
}