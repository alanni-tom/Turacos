package ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.Objects;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader();
        loader.setLocation(getClass().getResource("/fxml/MainUi.fxml"));

        Parent root = loader.load();

        Scene scene = new Scene(root, 740, 470);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/style/main.css")).toExternalForm());
        ThemeManager.apply(scene);

        primaryStage.setTitle("Turacos Login");
        primaryStage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/ico.png"))));

        primaryStage.setResizable(false);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
