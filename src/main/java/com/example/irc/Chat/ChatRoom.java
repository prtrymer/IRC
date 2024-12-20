package com.example.irc.Chat;

import java.util.ArrayList;
import java.util.List;

public class ChatRoom extends ChatComponent {
    private List<ChatComponent> components = new ArrayList<>();

    public ChatRoom(String name) {
        super(name);
    }

    @Override
    public void sendMessage(String message) {
        components.forEach(component -> component.sendMessage(message));
    }

    @Override
    public void addComponent(ChatComponent component) {
        components.add(component);
    }

    @Override
    public void removeComponent(ChatComponent component) {
        components.remove(component);
    }
}