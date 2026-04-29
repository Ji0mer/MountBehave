package com.example.onstepcontroller;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import javax.net.SocketFactory;

final class OnStepClient implements Closeable {
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final long COMMAND_GAP_MS = 180L;

    private SocketFactory socketFactory = SocketFactory.getDefault();
    private String host;
    private int port;
    private boolean configured;
    private long lastCommandAtMillis;

    synchronized String connect(String host, int port, SocketFactory socketFactory) throws IOException {
        this.socketFactory = socketFactory == null ? SocketFactory.getDefault() : socketFactory;
        this.host = host;
        this.port = port;
        configured = true;

        try {
            return handshakeQuery(":GVP#");
        } catch (IOException ex) {
            close();
            throw ex;
        }
    }

    synchronized boolean isConnected() {
        return configured;
    }

    synchronized void sendNoReply(String command) throws IOException {
        ensureConnected();
        throttleCommandStart();
        boolean sent = false;
        try (Socket commandSocket = openSocket()) {
            writeCommand(commandSocket, command);
            sent = true;
            sleepQuietly(COMMAND_GAP_MS);
        } catch (IOException ex) {
            if (!sent) {
                Logger.txFail(command, ex);
            } else {
                Logger.warn("TX_POST_SEND_FAIL " + command, ex);
            }
            throw ex;
        }
        lastCommandAtMillis = System.currentTimeMillis();
    }

    synchronized String query(String command) throws IOException {
        ensureConnected();
        throttleCommandStart();
        boolean sent = false;
        try (Socket commandSocket = openSocket()) {
            writeCommand(commandSocket, command);
            sent = true;
            BufferedInputStream input = new BufferedInputStream(commandSocket.getInputStream());
            String reply = readUntilHashOrClose(input);
            lastCommandAtMillis = System.currentTimeMillis();
            Logger.rx(command, reply);
            return reply;
        } catch (IOException ex) {
            if (sent) {
                Logger.rxFail(command, ex);
            } else {
                Logger.txFail(command, ex);
            }
            throw ex;
        }
    }

    private String handshakeQuery(String command) throws IOException {
        ensureConnected();
        throttleCommandStart();
        boolean sent = false;
        try (Socket commandSocket = openSocket()) {
            writeCommand(commandSocket, command);
            sent = true;
            BufferedInputStream input = new BufferedInputStream(commandSocket.getInputStream());
            try {
                String reply = readUntilHashOrClose(input);
                lastCommandAtMillis = System.currentTimeMillis();
                Logger.rx(command, reply);
                return reply;
            } catch (SocketTimeoutException ex) {
                lastCommandAtMillis = System.currentTimeMillis();
                Logger.warn("RX_TIMEOUT " + command, ex);
                Logger.rx(command, "");
                return "";
            }
        } catch (IOException ex) {
            if (sent) {
                Logger.rxFail(command, ex);
            } else {
                Logger.txFail(command, ex);
            }
            throw ex;
        }
    }

    @Override
    public synchronized void close() {
        configured = false;
        host = null;
        port = 0;
        socketFactory = SocketFactory.getDefault();
        lastCommandAtMillis = 0L;
    }

    private Socket openSocket() throws IOException {
        Socket commandSocket = socketFactory.createSocket();
        commandSocket.setKeepAlive(true);
        commandSocket.setTcpNoDelay(true);
        commandSocket.setReuseAddress(true);
        commandSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        commandSocket.setSoTimeout(READ_TIMEOUT_MS);
        return commandSocket;
    }

    private void writeCommand(Socket commandSocket, String command) throws IOException {
        OutputStream output = commandSocket.getOutputStream();
        output.write(command.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        Logger.txOk(command);
    }

    private void ensureConnected() throws IOException {
        if (!configured || host == null || host.isEmpty() || port <= 0) {
            throw new IOException("Not connected");
        }
    }

    private void throttleCommandStart() {
        long elapsed = System.currentTimeMillis() - lastCommandAtMillis;
        if (lastCommandAtMillis > 0L && elapsed < COMMAND_GAP_MS) {
            sleepQuietly(COMMAND_GAP_MS - elapsed);
        }
    }

    private String readUntilHashOrClose(BufferedInputStream input) throws IOException {
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
                throw new IOException(reply.length() > 0
                        ? "Connection closed mid-reply: " + reply
                        : "Connection closed");
            }
            if (next == '#') {
                return reply.toString();
            }
            reply.append((char) next);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
