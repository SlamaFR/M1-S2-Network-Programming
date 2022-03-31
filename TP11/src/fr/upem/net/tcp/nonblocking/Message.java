package fr.upem.net.tcp.nonblocking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public record Message(String nickname, String message) {

    @Override
    public String toString() {
        return nickname + ": " + message;
    }

    public ByteBuffer toByteBuffer() {
        var nickname = StandardCharsets.UTF_8.encode(this.nickname);
        var message = StandardCharsets.UTF_8.encode(this.message);
        var buffer = ByteBuffer.allocate(nickname.remaining() + message.remaining() + 2 * Integer.BYTES);
        buffer.putInt(nickname.remaining());
        buffer.put(nickname);
        buffer.putInt(message.remaining());
        buffer.put(message);
        buffer.flip();
        return buffer;
    }
}
