package com.example.irc.Connection.Server;

import com.example.irc.Connection.Connection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ServerConnection implements Connection {
    private final Socket clientSocket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    public ServerConnection(Socket clientSocket) throws IOException {
        this.clientSocket = clientSocket;
        this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        this.writer = new PrintWriter(clientSocket.getOutputStream(), true);
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
        clientSocket.close();
    }

    @Override
    public boolean isConnected() {
        return clientSocket != null && !clientSocket.isClosed() && clientSocket.isConnected();
    }
}
