package fr.upem.net.tcp.nonblocking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class MessageReader implements Reader<Message> {

    private enum State {
        DONE, WAITING, ERROR
    }

    private State state = State.WAITING;
    private final StringReader stringReader = new StringReader();
    private String nickname;
    private String content;
    private Message message;


    @Override
    public ProcessStatus process(ByteBuffer buffer) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        var nicknameState = stringReader.process(buffer);
        if (nicknameState != ProcessStatus.DONE) {
            return nicknameState;
        }
        nickname = stringReader.get();
        stringReader.reset();
        var contentState = stringReader.process(buffer);
        if (contentState != ProcessStatus.DONE) {
            return contentState;
        }
        content = stringReader.get();
        state = State.DONE;
        message = new Message(nickname, content);
        return ProcessStatus.DONE;
    }

    @Override
    public Message get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return message;
    }

    @Override
    public void reset() {
        state = State.WAITING;
        stringReader.reset();
    }
}