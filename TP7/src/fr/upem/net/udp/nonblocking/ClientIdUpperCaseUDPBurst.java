package fr.upem.net.udp.nonblocking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public class ClientIdUpperCaseUDPBurst {

    private static final Logger LOGGER = Logger.getLogger(ClientIdUpperCaseUDPBurst.class.getName());
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final int BUFFER_SIZE = 1024;

    private enum State {
        SENDING, RECEIVING, FINISHED
    }

    private final BitSet bitSet;
    private final ByteBuffer[] buffers;
    private final String[] upperCaseLines;
    private final InetSocketAddress serverAddress;
    private final DatagramChannel dc;
    private final Selector selector;
    private final SelectionKey uniqueKey;
    private final ByteBuffer receiveBuffer;
    private final long timeout;

    // JE SAIS QUE LE CODE EST IMMONDE
    // ON NE JUUUUUUUUUUUUGE PAS :^)

    private State state;
    private long lastSend;
    private int currentId;

    private static void usage() {
        System.err.println("Usage : ClientIdUpperCaseUDPOneByOne in-filename out-filename timeout host port ");
    }

    private ClientIdUpperCaseUDPBurst(ByteBuffer[] buffers, long timeout, InetSocketAddress serverAddress,
                                      DatagramChannel dc, Selector selector, SelectionKey uniqueKey) {
        this.bitSet = new BitSet(buffers.length);
        bitSet.flip(0, buffers.length);
        this.buffers = buffers;
        this.timeout = timeout;
        this.serverAddress = serverAddress;
        this.dc = dc;
        this.selector = selector;
        this.uniqueKey = uniqueKey;
        this.receiveBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        this.state = State.SENDING;
        this.upperCaseLines = new String[buffers.length];
    }

    public static ClientIdUpperCaseUDPBurst create(String inFilename, long timeout,
                                                   InetSocketAddress serverAddress) throws IOException {
        Objects.requireNonNull(inFilename);
        Objects.requireNonNull(serverAddress);
        Objects.checkIndex(timeout, Long.MAX_VALUE);

        // Read all lines of inFilename opened in UTF-8
        var lines = Files.readAllLines(Path.of(inFilename), UTF8);
        var dc = DatagramChannel.open();
        dc.configureBlocking(false);
        dc.bind(null);
        var selector = Selector.open();
        var uniqueKey = dc.register(selector, SelectionKey.OP_WRITE);

        var buffers = IntStream.range(0, lines.size()).mapToObj(i -> {
            var buffer = ByteBuffer.allocate(BUFFER_SIZE);
            buffer.putLong(i);
            buffer.put(UTF8.encode(lines.get(i)));
            return buffer;
        }).toArray(ByteBuffer[]::new);
        return new ClientIdUpperCaseUDPBurst(buffers, timeout, serverAddress, dc, selector, uniqueKey);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            usage();
            return;
        }

        var inFilename = args[0];
        var outFilename = args[1];
        var timeout = Long.parseLong(args[2]);
        var server = new InetSocketAddress(args[3], Integer.parseInt(args[4]));

        // Create client with the parameters and launch it
        var upperCaseLines = create(inFilename, timeout, server).launch();

        Files.write(Path.of(outFilename), upperCaseLines, UTF8, CREATE, WRITE, TRUNCATE_EXISTING);
    }

    private List<String> launch() throws IOException {
        try {
            while (!isFinished()) {
                try {
                    selector.select(this::treatKey, updateInterestOps());
                } catch (UncheckedIOException tunneled) {
                    throw tunneled.getCause();
                }
            }
            return Arrays.asList(upperCaseLines);
        } finally {
            dc.close();
        }
    }

    private void treatKey(SelectionKey key) {
        try {
            if (key.isValid() && key.isWritable()) {
                doWrite();
            }
            if (key.isValid() && key.isReadable()) {
                doRead();
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    /**
     * Updates the interestOps on key based on state of the context
     *
     * @return the timeout for the next select (0 means no timeout)
     */
    private long updateInterestOps() {
        switch (state) {
            case RECEIVING:
                var elapsed = System.currentTimeMillis() - lastSend;
                if (elapsed < timeout) {
                    uniqueKey.interestOps(SelectionKey.OP_READ);
                    return timeout - elapsed;
                }
                LOGGER.warning("Didn't received expected packet, retrying");
                state = State.SENDING;
                currentId = 0;
            case SENDING:
                uniqueKey.interestOps(SelectionKey.OP_WRITE);
                return 0;
            default:
                throw new AssertionError("Unexpected state: " + state);
        }
    }

    private boolean isFinished() {
        return state == State.FINISHED;
    }

    /**
     * Performs the receptions of packets
     *
     * @throws IOException
     */
    private void doRead() throws IOException {
        var sender = dc.receive(receiveBuffer);
        if (sender == null) {
            return;
        }
        receiveBuffer.flip();
        if (receiveBuffer.remaining() < Long.BYTES) {
            LOGGER.warning("Malformed packet");
            return;
        }
        var id = receiveBuffer.getLong();
        if (!bitSet.get((int) id)) {
            return;
        }
        LOGGER.info("Received packet " + id);
        upperCaseLines[(int) id] = UTF8.decode(receiveBuffer).toString();
        bitSet.set((int) id, false);
        receiveBuffer.clear();

        if (bitSet.cardinality() == 0) {
            state = State.FINISHED;
        }
    }

    /**
     * Tries to send the packets
     *
     * @throws IOException
     */
    private void doWrite() throws IOException {
        if (bitSet.get(currentId)) {
            dc.send(buffers[currentId].flip(), serverAddress);
            LOGGER.info("Tried so send packet " + currentId);
        }

        currentId++;
        lastSend = System.currentTimeMillis();
        receiveBuffer.clear();
        if (currentId == buffers.length) {
            state = State.RECEIVING;
        }
    }
}