package com.example.irc.Chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatRoom {
    private final String name;
    private final Set<ChatUser> users = ConcurrentHashMap.newKeySet();

    public ChatRoom(String name) {
        this.name = name;
    }

    public int getUserCount() {
        return users.size();
    }

    public Set<ChatUser> getUsers() {
        return users;
    }

    public void addComponent(ChatUser user) {
        users.add(user);
    }

    public void removeComponent(ChatUser user) {
        users.remove(user);
    }

    public void sendMessage(String message) {
        users.forEach(user -> user.sendMessage(message));
    }
}