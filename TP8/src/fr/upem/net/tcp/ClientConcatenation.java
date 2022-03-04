package fr.upem.net.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.logging.Logger;

public class ClientConcatenation {

    public static final Logger logger = Logger.getLogger(ClientConcatenation.class.getName());
    public static final Charset UTF8 = StandardCharsets.UTF_8;

    private static List<String> getStrings() {
        var strings = new ArrayList<String>();
        try (var scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                var line = scanner.nextLine();
                if (line.isEmpty()) {
                    break;
                }
                strings.add(line);
            }
        }
        return strings;
    }

    private static boolean checkConcatenation(List<String> lines, String response) {
        return String.join(",", lines).equals(response);
    }

    /**
     * Write all the longs in list in BigEndian on the server and read the long sent
     * by the server and returns it
     *
     * returns Optional.empty if the protocol is not followed by the server but no
     * IOException is thrown
     *
     */
    private static Optional<String> requestConcatenation(SocketChannel sc, List<String> list) throws IOException {
        var buffers = list.stream().map(UTF8::encode).toList();
        var sizes = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        var buffer = ByteBuffer.allocate(Integer.BYTES + Integer.BYTES * list.size() + sizes);
        buffer.putInt(list.size());
        for (var b : buffers) {
            buffer.putInt(b.remaining());
            buffer.put(b);
        }
        sc.write(buffer.flip()); // Should have used a finite-sized buffer, and filling/writing multiple times (as needed)

        buffer = ByteBuffer.allocate(Integer.BYTES);
        if (!ClientEOS.readFully(sc, buffer)) {
            return Optional.empty();
        }
        buffer = ByteBuffer.allocate(buffer.flip().getInt());
        if (!ClientEOS.readFully(sc, buffer)) {
            return Optional.empty();
        }
        return Optional.of(UTF8.decode(buffer.flip()).toString());
    }

    public static void main(String[] args) throws IOException {
        var server = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
        try (var sc = SocketChannel.open(server)) {
            var list = getStrings();

            var concatenation = requestConcatenation(sc, list);
            if (concatenation.isEmpty()) {
                logger.warning("Connection with server lost.");
                return;
            }
            if (!checkConcatenation(list, concatenation.get())) {
                logger.warning("Oups! Something wrong happened!");
            }

            logger.info("Everything seems ok");
        }
    }
}
