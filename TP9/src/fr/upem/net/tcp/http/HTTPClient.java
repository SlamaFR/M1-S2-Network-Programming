package fr.upem.net.tcp.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static java.nio.charset.StandardCharsets.US_ASCII;

public record HTTPClient(InetSocketAddress server, String resource) {

    public String get() throws IOException {
        try (var sc = SocketChannel.open()) {
            sc.connect(server);
            var request = US_ASCII.encode("GET " + resource + " HTTP/1.1\r\nHost:" + server.getHostName() + "\r\n\r\n");
            sc.write(request);
            sc.shutdownOutput();

            var reader = new HTTPReader(sc, ByteBuffer.allocate(2048));
            var header = reader.readHeader();

            if (header.getCode() == 301 || header.getCode() == 302) {
                var newLocation = new URL(header.getFields().getOrDefault("location", ""));
                var newServer = new InetSocketAddress(newLocation.getHost(), 80);
                return new HTTPClient(newServer, newLocation.getPath()).get();
            }

            if (header.getCode() != 200) {
                return "";
            }

            var contentType = header.getContentType().orElse("");
            if (!contentType.equalsIgnoreCase("text/html")) {
                return null;
            }
            var charset = header.getCharset().orElse(US_ASCII);
            if (header.isChunkedTransfer()) {
                return charset.decode(reader.readChunks().flip()).toString();
            } else {
                var contentLength = header.getContentLength();
                return charset.decode(reader.readBytes(contentLength).flip()).toString();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        var google = new InetSocketAddress("google.com", 80);
        var res = new HTTPClient(google, "/").get();
        System.out.println(res);
    }

}
