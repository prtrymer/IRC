package com.example.irc.MessageHandler;

public interface MessageCallback {
    void onNicknameChange(String newNickname);
    void onChannelChange(String channel);
    void onRegistrationStatus(boolean registered);
    void onServerMessage(String message);
    void sendServerCommand(String command);
    void closeConnection();
    void reconnectToServer(String host, int port);
}
