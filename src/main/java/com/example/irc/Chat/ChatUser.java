package com.example.irc.Chat;

import java.io.PrintWriter;

public class ChatUser extends ChatComponent {
    private PrintWriter out;

    public ChatUser(String name, PrintWriter out) {
        super(name);
        this.out = out;
    }

    @Override
    public void sendMessage(String message) {
        out.println(message);
    }

    @Override
    public void addComponent(ChatComponent component) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeComponent(ChatComponent component) {
        throw new UnsupportedOperationException();
    }
}
