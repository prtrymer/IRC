package com.example.irc;

import com.example.irc.Chat.ChatRoom;
import com.example.irc.Chat.ChatUser;
import com.example.irc.User.User;
import com.example.irc.User.UserDatabaseSingleton;
import com.example.irc.User.UserService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IRCServer {
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final ChatRoom mainRoom;
    private final UserDatabaseSingleton userDatabase;
    private final ServerConfig serverConfig;
    private volatile boolean running;

    @Autowired
    public IRCServer(UserService userService, ServerConfig serverConfig) {
        this.userDatabase = UserDatabaseSingleton.getInstance(userService);
        this.serverConfig = serverConfig;
        this.mainRoom = new ChatRoom("Main");
    }

    @PostConstruct
    public void startServer() {
        running = true;
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(serverConfig.getDefaultPort())) {
                System.out.println("IRC Сервер запущено на порту " + serverConfig.getDefaultPort());

                while (running) {
                    Socket socket = serverSocket.accept();
                    ClientHandler clientHandler = new ClientHandler(socket);
                    clients.add(clientHandler);
                    new Thread(clientHandler).start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void stop() {
        running = false;
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private User user;

        public ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.out = new PrintWriter(socket.getOutputStream(), true);
        }

        @Override
        public void run() {
            try {
                handleRegistrationOrLogin();

                String message;
                while ((message = in.readLine()) != null) {
                    if (message.startsWith("/")) {
                        handleCommand(message);
                    } else {
                        mainRoom.sendMessage(user.getUsername() + ": " + message);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                cleanup();
            }
        }

        private void handleRegistrationOrLogin() throws IOException {
            out.println("Введіть команду /register username password email або /login username password");
            String input = in.readLine();
            String[] parts = input.split(" ");

            if (parts[0].equals("/register")) {
                Optional<User> newUser = userDatabase.registerUser(parts[1], parts[2], parts[3]);
                if (newUser.isPresent()) {
                    user = newUser.get();
                    out.println("Реєстрація успішна!");
                } else {
                    out.println("Користувач вже існує!");
                    handleRegistrationOrLogin();
                }
            } else if (parts[0].equals("/login")) {
                Optional<User> authenticatedUser = userDatabase.authenticateUser(parts[1], parts[2]);
                if (authenticatedUser.isPresent()) {
                    user = authenticatedUser.get();
                    out.println("Вхід успішний!");
                } else {
                    out.println("Невірні дані!");
                    handleRegistrationOrLogin();
                }
            }

            if (user != null) {
                user.setOnline(true);
                mainRoom.addComponent(new ChatUser(user.getUsername(), out));
                mainRoom.sendMessage("Користувач " + user.getUsername() + " приєднався до чату");
            }
        }

        private void handleCommand(String command) {
            String[] parts = command.split(" ");
            switch (parts[0].toLowerCase()) {
                case "/nick":
                    if (parts.length > 1) {
                        String oldUsername = user.getUsername();
                        user.setUsername(parts[1]);
                        mainRoom.sendMessage(String.format("Користувач %s змінив нікнейм на %s",
                                oldUsername, parts[1]));
                    }
                    break;

                case "/quit":
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;

                case "/help":
                    out.println("Доступні команди:");
                    out.println("/nick <new_name> - змінити нікнейм");
                    out.println("/quit - вийти з чату");
                    out.println("/help - показати це повідомлення");
                    break;

                default:
                    out.println("Невідома команда. Використайте /help для списку команд");
            }
        }

        private void cleanup() {
            if (user != null) {
                user.setOnline(false);
                mainRoom.sendMessage("Користувач " + user.getUsername() + " покинув чат");
            }
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
