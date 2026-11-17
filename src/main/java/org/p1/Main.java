package org.p1;

import org.p1.controller.FileController;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            FileController fileController = new FileController(8081);
            fileController.start();
            System.out.println("Server started on port 8081");
            System.out.println("UI available at port 3000");
            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            () -> {
                                System.out.println("Shutting down the server.");
                                fileController.stop();
                            }
                    )
            );
            System.out.println("Press enter to stop the server.");
            System.in.read();
        } catch (Exception e) {
            System.err.println("Failed to start the server at port 8081. " + e.getMessage());
            e.printStackTrace();
        }
    }
}