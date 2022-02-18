package fr.upem.net.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class ReadFileWithEncoding {

    private static void usage() {
        System.err.println("Usage: ReadFileWithEncoding charset filename");
    }

    private static String stringFromFile(Charset cs, Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining()) {
                channel.read(buffer);
            }
            buffer.flip();
            return cs.decode(buffer).toString();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            usage();
            return;
        }
        var cs = Charset.forName(args[0]);
        var path = Path.of(args[1]);
        System.out.print(stringFromFile(cs, path));
    }
}
