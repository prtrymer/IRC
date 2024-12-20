package com.example.irc.Connection;

import com.example.irc.MessageHandler.MessageHandler;

import java.net.Socket;

public interface ConnectionFactory {
    Connection createConnection(Socket socket) throws Exception;
    MessageHandler createMessageHandler();
}
