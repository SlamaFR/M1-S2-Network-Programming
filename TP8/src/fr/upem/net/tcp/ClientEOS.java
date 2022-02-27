package fr.upem.net.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class ClientEOS {

    public static final Charset UTF8_CHARSET = StandardCharsets.UTF_8;
    public static final int BUFFER_SIZE = 1024;
    public static final Logger LOGGER = Logger.getLogger(ClientEOS.class.getName());

    /**
     * This method:
     * - connect to server
     * - writes the bytes corresponding to request in UTF8
     * - closes the write-channel to the server
     * - stores the bufferSize first bytes of server response
     * - return the corresponding string in UTF8
     *
     * @return the UTF8 string corresponding to bufferSize first bytes of server
     * response
     */

    public static String getFixedSizeResponse(String request, SocketAddress server, int bufferSize) throws IOException {
        try (var sc = SocketChannel.open()) {
            sc.connect(server);
            var buffer = UTF8_CHARSET.encode(request);
            sc.write(buffer);
            sc.shutdownOutput();

            buffer = ByteBuffer.allocate(bufferSize);
            if (readFully(sc, buffer)) {
                LOGGER.info("Read " + buffer.capacity() + " bytes");
            } else {
                LOGGER.info("Connection closed for reading");
            }
            return UTF8_CHARSET.decode(buffer.flip()).toString();
        }
    }

    /**
     * This method:
     * - connect to server
     * - writes the bytes corresponding to request in UTF8
     * - closes the write-channel to the server
     * - reads and stores all bytes from server until read-channel is closed
     * - return the corresponding string in UTF8
     *
     * @return the UTF8 string corresponding the full response of the server
     */

    public static String getUnboundedResponse(String request, SocketAddress server) throws IOException {
        try (var sc = SocketChannel.open()) {
            sc.connect(server);
            var buffer = UTF8_CHARSET.encode(request);
            sc.write(buffer);
            sc.shutdownOutput();

            buffer = ByteBuffer.allocate(BUFFER_SIZE);
            var done = false;
            do {
                done = !readFully(sc, buffer);
                if (!buffer.hasRemaining()) {
                    buffer = grow(buffer);
                }
            } while (!done);
            LOGGER.info("Read " + buffer.capacity() + " bytes");
            return UTF8_CHARSET.decode(buffer.flip()).toString();
        }
    }

    public static ByteBuffer grow(ByteBuffer buffer) {
        var newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
        newBuffer.put(buffer);
        return newBuffer;
    }

    /**
     * Fill the workspace of the Bytebuffer with bytes read from sc.
     *
     * @return false if read returned -1 at some point and true otherwise
     */
    static boolean readFully(SocketChannel sc, ByteBuffer buffer) throws IOException {
        var read = 0;
        do {
            read = sc.read(buffer);
        } while (read != -1 && buffer.hasRemaining());
        return read != -1;
    }

    public static void main(String[] args) throws IOException {
        var google = new InetSocketAddress("www.google.fr", 80);
        System.out.println("Read fixed:");
        System.out.println(getFixedSizeResponse("GET / HTTP/1.1\r\nHost: www.google.fr\r\n\r\n", google, 512));
        System.out.println("\nRead unbounded:");
        System.out.println(getUnboundedResponse("GET / HTTP/1.1\r\nHost: www.google.fr\r\n\r\n", google));
    }
}
