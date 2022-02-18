package fr.upem.net.udp;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class ClientIdUpperCaseUDPBurst {

    private static Logger logger = Logger.getLogger(ClientIdUpperCaseUDPBurst.class.getName());
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final int BUFFER_SIZE = 1024;
    private final List<String> lines;
    private final int nbLines;
    private final String[] upperCaseLines; //
    private final int timeout;
    private final String outFilename;
    private final InetSocketAddress serverAddress;
    private final DatagramChannel dc;
    private final AnswersLog answersLog;         // Thread-safe structure keeping track of missing responses

    public static void usage() {
        System.out.println("Usage : ClientIdUpperCaseUDPBurst in-filename out-filename timeout host port ");
    }

    public ClientIdUpperCaseUDPBurst(List<String> lines, int timeout, InetSocketAddress serverAddress, String outFilename) throws IOException {
        this.lines = lines;
        this.nbLines = lines.size();
        this.timeout = timeout;
        this.outFilename = outFilename;
        this.serverAddress = serverAddress;
        this.dc = DatagramChannel.open();
        dc.bind(null);
        this.upperCaseLines = new String[nbLines];
        this.answersLog = new AnswersLog(nbLines); // TODO
    }

    private void senderThreadRun() {
        var buffers = IntStream.range(0, nbLines).mapToObj(i -> {
            var buffer = ByteBuffer.allocate(BUFFER_SIZE);
            buffer.putLong(i);
            buffer.put(UTF8.encode(lines.get(i)));
            return buffer;
        }).toArray(ByteBuffer[]::new);
        while (true) {
            try {
                for (int id : answersLog.allNotSet()) {
                    dc.send(buffers[id].flip(), serverAddress);
                    System.out.println("=>> " + id);
                }
                Thread.sleep(timeout);
            } catch (AsynchronousCloseException e) {
                logger.info("Sender closed");
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                logger.severe(e.getMessage());
            }
        }
    }

    public void launch() throws IOException {
        var buffer = ByteBuffer.allocate(BUFFER_SIZE);

        Thread senderThread = new Thread(this::senderThreadRun);
        senderThread.start();

        try {
            while (!answersLog.full()) {
                buffer.clear();
                dc.receive(buffer);
                buffer.flip();

                if (buffer.remaining() < Long.BYTES) {
                    logger.severe("Malformed packet");
                    continue;
                }

                var id = buffer.getLong();
                var message = UTF8.decode(buffer).toString();
                System.out.println("Received: " + message + " of id " + id);
                answersLog.set((int) id);
                upperCaseLines[(int) id] = message;
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Fatal error in listener", e);
        }

        senderThread.interrupt();
        Files.write(Paths.get(outFilename), Arrays.asList(upperCaseLines), UTF8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        dc.close();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 5) {
            usage();
            return;
        }

        String inFilename = args[0];
        String outFilename = args[1];
        int timeout = Integer.valueOf(args[2]);
        String host = args[3];
        int port = Integer.valueOf(args[4]);
        InetSocketAddress serverAddress = new InetSocketAddress(host, port);

        //Read all lines of inFilename opened in UTF-8
        List<String> lines = Files.readAllLines(Paths.get(inFilename), UTF8);
        //Create client with the parameters and launch it
        ClientIdUpperCaseUDPBurst client = new ClientIdUpperCaseUDPBurst(lines, timeout, serverAddress, outFilename);
        client.launch();
    }

    private static class AnswersLog {

        private final BitSet bitSet;

        public AnswersLog(int size) {
            this.bitSet = new BitSet(size);
            this.bitSet.flip(0, size);
        }

        public void set(int id) {
            synchronized (bitSet) {
                bitSet.set(id, false);
            }
        }

        public int[] allNotSet() {
            synchronized (bitSet) {
                return bitSet.stream().toArray();
            }
        }

        public boolean full() {
            synchronized (bitSet) {
                return bitSet.cardinality() == 0;
            }
        }
    }
}


