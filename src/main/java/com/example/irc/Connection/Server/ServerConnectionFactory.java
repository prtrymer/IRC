package com.example.irc.Connection.Server;

import com.example.irc.Connection.Connection;
import com.example.irc.Connection.ConnectionFactory;
import com.example.irc.MessageHandler.MessageHandler;
import com.example.irc.MessageHandler.ServerMessageHandler;

import java.io.IOException;
import java.net.Socket;

public class ServerConnectionFactory implements ConnectionFactory {
    @Override
    public Connection createConnection(Socket socket) throws IOException {
        return new ServerConnection(socket);
    }

    @Override
    public MessageHandler createMessageHandler() {
        return new ServerMessageHandler();
    }
}

