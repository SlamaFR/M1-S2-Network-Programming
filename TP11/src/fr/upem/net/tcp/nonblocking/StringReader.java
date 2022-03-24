package fr.upem.net.tcp.nonblocking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class StringReader implements Reader<String> {

    private enum State {
        DONE, WAITING_LENGTH, WAITING_CONTENT, ERROR
    }

    private State state = State.WAITING_LENGTH;
    private final IntReader intReader = new IntReader();
    private final ByteBuffer internalBuffer = ByteBuffer.allocate(1024); // write-mode
    private int messageLength;
    private String message;

    private ProcessStatus getMessageLength(ByteBuffer buffer) {
        var state = intReader.process(buffer);
        if (state != ProcessStatus.DONE) {
            return state;
        }
        messageLength = intReader.get();
        if (messageLength < 0 || messageLength > 1024) {
            return ProcessStatus.ERROR;
        }
        return ProcessStatus.DONE;
    }

    @Override
    public ProcessStatus process(ByteBuffer buffer) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        if (state == State.WAITING_LENGTH) {
            var lengthState = getMessageLength(buffer);
            if (lengthState != ProcessStatus.DONE) {
                return lengthState;
            }
            internalBuffer.limit(messageLength);
            state = State.WAITING_CONTENT;
        }
        if (state == State.WAITING_CONTENT) {
            buffer.flip();
            try {
                if (buffer.remaining() <= internalBuffer.remaining()) {
                    internalBuffer.put(buffer);
                } else {
                    var oldLimit = buffer.limit();
                    buffer.limit(internalBuffer.remaining());
                    internalBuffer.put(buffer);
                    buffer.limit(oldLimit);
                }
            } finally {
                buffer.compact();
            }
        }
        if (internalBuffer.hasRemaining()) {
            return ProcessStatus.REFILL;
        }
        state = State.DONE;
        internalBuffer.flip();
        message = StandardCharsets.UTF_8.decode(internalBuffer).toString();
        return ProcessStatus.DONE;
    }

    @Override
    public String get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return message;
    }

    @Override
    public void reset() {
        state = State.WAITING_LENGTH;
        internalBuffer.clear();
        intReader.reset();
    }
}