package fr.upem.net.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Scanner;

public class StoreWithByteOrder {

    private static void usage() {
        System.err.println("Usage: StoreWithByteOrder [LE|BE] <File>");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            usage();
            System.exit(1);
        }
        if (!"LE".equalsIgnoreCase(args[0]) && !"BE".equalsIgnoreCase(args[0])) {
            usage();
            System.exit(1);
        }

        var littleEndian = "LE".equalsIgnoreCase(args[0]);
        var path = Path.of(args[1]);
        var buffer = ByteBuffer.allocateDirect(Long.BYTES);
        buffer.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        try (var channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             var scanner = new Scanner(System.in)) {
            while (scanner.hasNextLong()) {
                buffer.putLong(scanner.nextLong());
                buffer.flip();
                channel.write(buffer);
                buffer.clear();
            }
        }

    }

}
