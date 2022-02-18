package fr.upem.net.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.Charset;

public class ReadStandardInputWithEncoding {

    private static final int BUFFER_SIZE = 1024;

    private static void usage() {
        System.err.println("Usage: ReadStandardInputWithEncoding charset");
    }

    private static ByteBuffer grow(ByteBuffer buffer) {
        var newBuffer = ByteBuffer.allocate(buffer.capacity() << 1);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }

    private static String stringFromStandardInput(Charset cs) throws IOException {
        try (var channel = Channels.newChannel(System.in)) {
            var buffer = ByteBuffer.allocate(BUFFER_SIZE);
            while (channel.read(buffer) != -1) {
                if (!buffer.hasRemaining()) {
                    buffer = grow(buffer);
                }
            }
            buffer.flip();
            return cs.decode(buffer).toString();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            usage();
            return;
        }
        Charset cs = Charset.forName(args[0]);
        System.out.print(stringFromStandardInput(cs));
    }
}
