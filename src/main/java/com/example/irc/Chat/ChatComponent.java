package com.example.irc.Chat;

public abstract class ChatComponent {
    protected String name;

    public ChatComponent(String name) {
        this.name = name;
    }

    public abstract void sendMessage(String message);
    public abstract void addComponent(ChatComponent component);
    public abstract void removeComponent(ChatComponent component);
}
