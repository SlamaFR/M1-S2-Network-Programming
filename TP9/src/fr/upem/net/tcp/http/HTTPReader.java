package fr.upem.net.tcp.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

public class HTTPReader {

    private final SocketChannel sc;
    private final ByteBuffer buffer;

    public HTTPReader(SocketChannel sc, ByteBuffer buffer) {
        this.sc = sc;
        this.buffer = buffer;
    }

    /**
     * @return The ASCII string terminated by CRLF without the CRLF
     * <p>
     * The method assume that buffer is in write mode and leaves it in
     * write mode The method process the data from the buffer and if necessary
     * will read more data from the socket.
     * @throws IOException HTTPException if the connection is closed before a line
     *                     could be read
     */
    public String readLineCRLF() throws IOException {
        var builder = new StringBuilder();

        buffer.flip();
        byte previous = 0;
        byte c = 0;
        while (previous != '\r' || c != '\n') {
            previous = c;
            if (!buffer.hasRemaining()) {
                buffer.clear();
                if (sc.read(buffer) == -1) {
                    throw new HTTPException("Invalid packet");
                }
                buffer.flip();
            }
            c = buffer.get();
            builder.append((char) c);
        }
        buffer.compact();

        return builder.substring(0, builder.length() - 2);
    }

    /**
     * @return The HTTPHeader object corresponding to the header read
     * @throws IOException HTTPException if the connection is closed before a header
     *                     could be read or if the header is ill-formed
     */
    public HTTPHeader readHeader() throws IOException {
        var fields = new HashMap<String, String>();

        var response = readLineCRLF();
        String line;
        do {
            line = readLineCRLF();
            if (!line.isEmpty()) {
                var split = line.split(": ");
                fields.compute(split[0], (k, v) -> v == null ? split[1] : v + ";" + split[1]);
            }
        } while (!line.isEmpty());
        return HTTPHeader.create(response, fields);
    }

    /**
     * @param size
     * @return a ByteBuffer in write mode containing size bytes read on the socket
     * <p>
     * The method assume that buffer is in write mode and leaves it in
     * write mode The method process the data from the buffer and if necessary
     * will read more data from the socket.
     * @throws IOException HTTPException is the connection is closed before all
     *                     bytes could be read
     */
    public ByteBuffer readBytes(int size) throws IOException {
        var result = ByteBuffer.allocate(size);

        buffer.flip();
        while (result.hasRemaining()) {
            if (!buffer.hasRemaining()) {
                buffer.clear();
                if (sc.read(buffer) == -1) {
                    return result;
                }
                buffer.flip();
            }
            result.put(buffer.get());
        }
        buffer.compact();

        return result;
    }

    /**
     * @return a ByteBuffer in write-mode containing a content read in chunks mode
     * @throws IOException HTTPException if the connection is closed before the end
     *                     of the chunks if chunks are ill-formed
     */
    public ByteBuffer readChunks() throws IOException {
        var result = ByteBuffer.allocate(0);

        var size = -1;
        try {
            do {
                var e = readLineCRLF();
                size = Integer.parseInt(e, 16);
                var line = readBytes(size);
                readBytes(2);
                result = concat(result, line);
            } while (size != 0);
        } catch (NumberFormatException e) {
            throw new HTTPException("Malformed chunked body");
        }
        return result;
    }

    private ByteBuffer concat(ByteBuffer dst, ByteBuffer src) {
        dst.flip();
        src.flip();
        var newBuffer = ByteBuffer.allocate(dst.remaining() + src.remaining());
        newBuffer.put(dst);
        newBuffer.put(src);
        return newBuffer;
    }

    public static void main(String[] args) throws IOException {
        var charsetASCII = US_ASCII;
        var request = "GET / HTTP/1.1\r\n" + "Host: www.w3.org\r\n" + "\r\n";
        var sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.w3.org", 80));
        sc.write(charsetASCII.encode(request));
        var buffer = ByteBuffer.allocate(50);
        var reader = new HTTPReader(sc, buffer);
        System.out.println(reader.readLineCRLF());
        System.out.println(reader.readLineCRLF());
        System.out.println(reader.readLineCRLF());
        sc.close();

        buffer = ByteBuffer.allocate(50);
        sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.w3.org", 80));
        reader = new HTTPReader(sc, buffer);
        sc.write(charsetASCII.encode(request));
        System.out.println(reader.readHeader());
        sc.close();

        buffer = ByteBuffer.allocate(50);
        sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.w3.org", 80));
        reader = new HTTPReader(sc, buffer);
        sc.write(charsetASCII.encode(request));
        var header = reader.readHeader();
        System.out.println(header);
        var content = reader.readBytes(header.getContentLength());
        content.flip();
        System.out.println(header.getCharset().orElse(UTF_8).decode(content));
        sc.close();

        buffer = ByteBuffer.allocate(50);
        request = "GET / HTTP/1.1\r\n" + "Host: www.u-pem.fr\r\n" + "\r\n";
        sc = SocketChannel.open();
        sc.connect(new InetSocketAddress("www.u-pem.fr", 80));
        reader = new HTTPReader(sc, buffer);
        sc.write(charsetASCII.encode(request));
        header = reader.readHeader();
        System.out.println(header);
        content = reader.readChunks();
        content.flip();
        System.out.println(header.getCharset().orElse(UTF_8).decode(content));
        sc.close();
    }
}