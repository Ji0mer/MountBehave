package com.example.onstepcontroller;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

final class OnStepClient implements Closeable {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 1200;

    private Socket socket;
    private BufferedInputStream input;
    private OutputStream output;

    synchronized String connect(String host, int port) throws IOException {
        close();

        Socket nextSocket = new Socket();
        nextSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        nextSocket.setSoTimeout(READ_TIMEOUT_MS);

        socket = nextSocket;
        input = new BufferedInputStream(socket.getInputStream());
        output = socket.getOutputStream();

        try {
            return query(":GVP#");
        } catch (SocketTimeoutException ex) {
            return "";
        }
    }

    synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    synchronized void sendNoReply(String command) throws IOException {
        ensureConnected();
        output.write(command.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    synchronized String query(String command) throws IOException {
        ensureConnected();
        discardBufferedReply();
        sendNoReply(command);
        return readUntilHash();
    }

    @Override
    public synchronized void close() {
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        input = null;
        output = null;
        socket = null;
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected");
        }
    }

    private void discardBufferedReply() throws IOException {
        while (input.available() > 0) {
            int ignored = input.read();
            if (ignored < 0) {
                break;
            }
        }
    }

    private String readUntilHash() throws IOException {
        StringBuilder reply = new StringBuilder();
        while (true) {
            int next;
            try {
                next = input.read();
            } catch (SocketTimeoutException ex) {
                if (reply.length() > 0) {
                    return reply.toString();
                }
                throw ex;
            }
            if (next < 0) {
                throw new IOException("Connection closed");
            }
            if (next == '#') {
                return reply.toString();
            }
            reply.append((char) next);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
