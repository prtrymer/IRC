package com.example.irc;

import com.example.irc.Connection.Client.ClientConnectionFactory;
import com.example.irc.Connection.Connection;
import com.example.irc.Connection.ConnectionFactory;
import com.example.irc.MessageHandler.MessageHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class IRCClient {
    public static void main(String[] args) {
        String SERVER_ADDRESS = "localhost";
        int SERVER_PORT = 6667;

        try {
            // Підключення до сервера
            Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            ConnectionFactory factory = new ClientConnectionFactory();
            Connection connection = factory.createConnection(socket);
            MessageHandler messageHandler = factory.createMessageHandler();

            // Читання повідомлень від сервера в окремому потоці
            new Thread(() -> {
                try {
                    String message;
                    while ((message = connection.receive()) != null) {
                        System.out.println(message);
                    }
                } catch (IOException e) {
                    System.out.println("Втрачено з'єднання з сервером");
                }
            }).start();

            // Консольний ввід для надсилання повідомлень
            BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
            String userInput;

            // Спочатку реєстрація/логін
            System.out.println("Для реєстрації введіть: /register username password email");
            System.out.println("Для входу введіть: /login username password");

            while ((userInput = consoleReader.readLine()) != null) {
                messageHandler.handleMessage(userInput);
                connection.send(userInput);

                if (userInput.equalsIgnoreCase("/quit")) {
                    break;
                }
            }

            connection.close();

        } catch (IOException e) {
            System.out.println("Помилка підключення: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
