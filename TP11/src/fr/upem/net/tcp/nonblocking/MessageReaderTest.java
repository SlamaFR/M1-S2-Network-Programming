package fr.upem.net.tcp.nonblocking;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class MessageReaderTest {
    private static final Charset CS = StandardCharsets.UTF_8;


    @Test
    public void simpleTest() {
        var strUser = "ZwenDo";
        var strMsg = "tal vez !!";
        var user = CS.encode(strUser);
        var message = CS.encode(strMsg);

        var buffer = ByteBuffer.allocate(1_024);
        buffer.putInt(user.remaining()).put(user);
        buffer.putInt(message.remaining()).put(message);

        var mr = new MessageReader();
        assertEquals(Reader.ProcessStatus.DONE, mr.process(buffer));
        var msg = mr.get();

        assertEquals(strUser, msg.nickname());
        assertEquals(strMsg, msg.message());
    }

    @Test
    public void refill() {
        var strUser = "ZwenDo";
        var user = CS.encode(strUser);

        var buffer = ByteBuffer.allocate(1_024);
        buffer.putInt(user.remaining() + 1).put(user);

        var mr = new MessageReader();
        assertEquals(Reader.ProcessStatus.REFILL, mr.process(buffer));
    }

    @Test
    public void tooLargeSizeError() {
        var strUser = "ZwenDo";
        var user = CS.encode(strUser);

        var buffer = ByteBuffer.allocate(1_024);
        buffer.putInt(1025).put(user);

        var mr = new MessageReader();
        assertEquals(Reader.ProcessStatus.ERROR, mr.process(buffer));
    }

    @Test
    public void negativeSizeError() {
        var strUser = "ZwenDo";
        var user = CS.encode(strUser);

        var buffer = ByteBuffer.allocate(1_024);
        buffer.putInt(-1).put(user);

        var mr = new MessageReader();
        assertEquals(Reader.ProcessStatus.ERROR, mr.process(buffer));
    }
}
