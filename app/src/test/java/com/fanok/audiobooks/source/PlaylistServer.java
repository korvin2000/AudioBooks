package com.fanok.audiobooks.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A one-request HTTP server on the loopback that answers a playlist and remembers the request's Referer. */
final class PlaylistServer implements AutoCloseable {

    private final ServerSocket socket;
    private final Thread thread;
    private volatile String referer;

    PlaylistServer(String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        thread = new Thread(() -> serveOnce(bytes));
        thread.start();
    }

    /** The playlist's URL on this server. */
    String url() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/playlist.pl.txt";
    }

    /** The Referer of the request served; null when it had none. */
    String referer() throws InterruptedException {
        thread.join(5000);
        return referer;
    }

    private void serveOnce(byte[] body) {
        try (Socket client = socket.accept()) {
            BufferedReader request = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
            for (String line = request.readLine(); line != null && !line.isEmpty(); line = request.readLine()) {
                if (line.regionMatches(true, 0, "Referer:", 0, 8)) {
                    referer = line.substring(8).trim();
                }
            }
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // the test fails on its own assertions
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
