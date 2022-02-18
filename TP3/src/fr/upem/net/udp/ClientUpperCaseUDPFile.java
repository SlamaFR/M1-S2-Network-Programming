package fr.upem.net.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public class ClientUpperCaseUDPFile {
    private final static Charset UTF8 = StandardCharsets.UTF_8;

    private static void usage() {
        System.err.println("Usage : ClientUpperCaseUDPFile in-filename out-filename timeout host port ");
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 5) {
            usage();
            return;
        }

        var inFilename = args[0];
        var outFilename = args[1];
        var timeout = Integer.parseInt(args[2]);
        var server = new InetSocketAddress(args[3], Integer.parseInt(args[4]));

        // Read all lines of inFilename opened in UTF-8
        var lines = Files.readAllLines(Path.of(inFilename), UTF8);
        var upperCaseLines = new ArrayList<String>();

        var queue = new ArrayBlockingQueue<String>(32);

        try (var dc = DatagramChannel.open()) {
            dc.bind(null);

            var listener = new Thread(new Listener(dc, queue, UTF8));
            listener.setDaemon(true);
            listener.start();

            for (String line : lines) {
                var sendBuffer = UTF8.encode(line);
                System.out.println(">>> " + line);
                dc.send(sendBuffer, server);

                var poll = queue.poll(timeout, TimeUnit.MILLISECONDS);
                while (poll == null) {
                    sendBuffer.flip();
                    dc.send(sendBuffer, server);
                    System.out.println(">>> " + line);
                    poll = queue.poll(timeout, TimeUnit.MILLISECONDS);
                }
                System.out.println("<<< " + poll);
                upperCaseLines.add(poll);
            }
        }

        // Write upperCaseLines to outFilename in UTF-8
        Files.write(Path.of(outFilename), upperCaseLines, UTF8, CREATE, WRITE, TRUNCATE_EXISTING);
    }
}

/* Explication :
 *
 * Le programme envoie une ligne à transformer. Dans le pire des cas,
 * le proxy va mettre 300 ms à l'envoyer au serveur, le serveur va
 * process la chaîne et répondre en quelques instants. Le proxy peut
 * à nouveau mettre 300 ms à envoyer le résultat au client.
 * Ainsi le temps d'attente cumulé maximum est un peu plus de 600 ms.
 * Si on lance ce client avec un timeout autorisé de seulement 300 ms
 * voici le problème qui se crée :
 *
 *  - Le serveur envoie une chaine et attend son retour (1ere).
 *  - Le proxy va mettre 600 ms à répondre.
 *  - Le client a attendu 300 ms, il renvoie la chaine.
 *  - Le client reçoit une réponse (1ere en retard).
 *  - Le client envoie la chaine suivante (2nde).
 *  - Le client reçoit une réponse (1ere, renvoyée).
 *  - Le client envoie la chaine suivante (3e).
 *  - Le client reçoit une réponse (2nde).
 *  - ...
 *
 * Pour résoudre le problème, il faudrait simplement autorisée un délai
 * d'attente plus long, pour être sûr de ne rien louper, ni d'envoyer
 * des chaines plusieurs fois. Un bon délai serait 650 ms.
 */
