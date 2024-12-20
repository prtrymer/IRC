package com.example.irc.Connection.Client;

import com.example.irc.Connection.Connection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientConnection implements Connection {
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    public ClientConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.writer = new PrintWriter(socket.getOutputStream(), true);
    }

    @Override
    public void send(String message) {
        writer.println(message);
    }

    @Override
    public String receive() throws IOException {
        return reader.readLine();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    @Override
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }
}
