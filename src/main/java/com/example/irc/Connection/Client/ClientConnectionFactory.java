package com.example.irc.Connection.Client;

import com.example.irc.Connection.Connection;
import com.example.irc.Connection.ConnectionFactory;
import com.example.irc.MessageHandler.ClientMessageHandler;
import com.example.irc.MessageHandler.MessageHandler;

import java.io.IOException;
import java.net.Socket;

public class ClientConnectionFactory implements ConnectionFactory {
    @Override
    public Connection createConnection(Socket socket) throws IOException {
        return new ClientConnection(socket);
    }

    @Override
    public MessageHandler createMessageHandler() {
        return new ClientMessageHandler();
    }
}
