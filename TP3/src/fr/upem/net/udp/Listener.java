package fr.upem.net.udp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;

public class Listener implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(Listener.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final ByteBuffer buffer;
    private final DatagramChannel dc;
    private final ArrayBlockingQueue<String> queue;
    private final Charset cs;

    public Listener(DatagramChannel dc, ArrayBlockingQueue<String> queue, Charset cs) {
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.dc = dc;
        this.queue = queue;
        this.cs = cs;
    }

    @Override
    public void run() {
        while (true) {
            try {
                dc.receive(buffer);
                buffer.flip();
                queue.put(cs.decode(buffer).toString());
                buffer.clear();
            } catch (ClosedByInterruptException e) {
                LOGGER.info("Canal fermé par interruption ou interruption du listener");
                break;
            } catch (AsynchronousCloseException e) {
                LOGGER.info("Canal fermé");
                break;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
