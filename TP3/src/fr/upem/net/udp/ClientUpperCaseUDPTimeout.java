package fr.upem.net.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ClientUpperCaseUDPTimeout {

    private static void usage() {
        System.err.println("Usage : NetcatUDP host port charset");
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 3) {
            usage();
            return;
        }

        var server = new InetSocketAddress(args[0], Integer.parseInt(args[1]));
        var cs = Charset.forName(args[2]);
        var queue = new ArrayBlockingQueue<String>(64);

        try (var scanner = new Scanner(System.in); var dc = DatagramChannel.open()) {
            dc.bind(null);

            var listener = new Thread(new Listener(dc, queue, cs));
            listener.setDaemon(true);
            listener.start();

            while (scanner.hasNextLine()) {
                var line = scanner.nextLine();
                var sendBuffer = cs.encode(line);
                System.out.println("<<< " + line);
                dc.send(sendBuffer, server);


                var poll = queue.poll(1, TimeUnit.SECONDS);
                if (poll == null) {
                    System.out.println("[!] Le serveur n'a pas répondu");
                    continue;
                }
                System.out.printf(">>> Received %d bytes%n", poll.length());
                System.out.println(">>> " + poll);
            }
        }
    }
}
