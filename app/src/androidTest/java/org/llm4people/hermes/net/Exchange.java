package org.llm4people.hermes.net;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Real loopback HTTP exchanges and the exact requests sent by the Android client. */
public final class Exchange implements Closeable {
    private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    private final FutureTask<String> observation;

    public record Reply(String headers, String body) {}

    public Exchange(String status, String headers, String... replies) throws IOException {
        this(status, headers, () -> {}, replies);
    }

    public Exchange(String status, String headers, Runnable beforeReply, String... replies) throws IOException {
        this(status, beforeReply, Arrays.stream(replies).map(body -> new Reply(headers, body)).toArray(Reply[]::new));
    }

    public Exchange(String status, Reply... replies) throws IOException {
        this(status, () -> {}, replies);
    }

    private Exchange(String status, Runnable beforeReply, Reply... replies) throws IOException {
        observation = new FutureTask<>(() -> {
            StringBuilder requests = new StringBuilder();
            for (Reply reply : replies) requests.append(exchange(status, reply.headers(), beforeReply, reply.body()));
            return requests.toString();
        });
        Thread server = new Thread(observation, "hermes-test-server");
        server.setDaemon(true);
        server.start();
    }

    private String exchange(String status, String headers, Runnable beforeReply, String reply) throws IOException {
        byte[] body = reply.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = listener.accept()) {
            socket.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            StringBuilder wire = new StringBuilder();
            String line;
            int bytes = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                wire.append(line).append('\n');
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    bytes = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            for (int i = 0; i < bytes; i++) wire.append((char) reader.read());
            beforeReply.run();
            try {
                OutputStream out = socket.getOutputStream();
                String head = "HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: " +
                        body.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (java.net.SocketException clientClosed) {
                // Client revoked or closed its connection while beforeReply held the response.
            }
            return wire.toString();
        }
    }

    public String endpoint() { return "http://127.0.0.1:" + listener.getLocalPort() + "/p/travail/"; }
    HermesApi api() { return new HermesApi(endpoint(), "test-only"); }
    public String observed() throws Exception { return observation.get(4, TimeUnit.SECONDS); }
    @Override public void close() throws IOException { listener.close(); }
}
