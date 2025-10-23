package ui;

import controller.DatabaseConfig;
import controller.DatabaseConfigDAO;
import controller.controllers;
import database.psql;
import database.mysql;
import database.redis;
import database.mssql;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.collections.FXCollections;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Objects;

public class init {

    @FXML
    private ComboBox<String> ComboBox;
    @FXML
    private TextField portTextField;
    @FXML
    private TextField hostTextField;
    @FXML
    private TextField usernameTextField;
    @FXML
    private TextField passwordTextField;
    @FXML
    private TextField passwordPlainTextField;
    @FXML
    private javafx.scene.image.ImageView togglePasswordImage;
    @FXML
    private TextField databaseTextField;
    @FXML
    private ListView<DatabaseConfig> savedConfigsList;

    private DatabaseConfigDAO dao = new DatabaseConfigDAO();
    private final javafx.beans.property.BooleanProperty showPassword = new javafx.beans.property.SimpleBooleanProperty(false);
    private javafx.scene.image.Image eyeOpen;
    private javafx.scene.image.Image eyeClosed;


    @FXML
    private void initialize() {
        ComboBox.setItems(FXCollections.observableArrayList("MySQL", "PostgreSQL", "Redis", "SqlServer"));
        ComboBox.setValue("MySQL");
        portTextField.setText("3306");

        /**
         * 网友建议 显示密码功能。
         */
        passwordPlainTextField.visibleProperty().bind(showPassword);
        passwordPlainTextField.managedProperty().bind(showPassword);
        passwordTextField.visibleProperty().bind(showPassword.not());
        passwordTextField.managedProperty().bind(showPassword.not());
        passwordPlainTextField.textProperty().bindBidirectional(passwordTextField.textProperty());

        showPassword.set(false);
        eyeOpen = loadImageOrFallback("/images/look.png", "/images/ico.png");
        eyeClosed = loadImageOrFallback("/images/hidden.png", "/images/ico.png");
        if (togglePasswordImage != null) {
            togglePasswordImage.setImage(eyeClosed);
        }

        savedConfigsList.setItems(FXCollections.observableArrayList(dao.getAllConfigs()));

        savedConfigsList.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                populateFields(newSelection);
            }
        });

        ContextMenu ctx = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除配置");
        deleteItem.setOnAction(e -> {
            DatabaseConfig cfg = savedConfigsList.getSelectionModel().getSelectedItem();
            if (cfg != null) {
                dao.deleteConfig(cfg.getDbType(), cfg.getHost(), cfg.getPort(), cfg.getDatabase());
                savedConfigsList.setItems(FXCollections.observableArrayList(dao.getAllConfigs()));
            }
        });
        ctx.getItems().addAll(deleteItem);
        savedConfigsList.setContextMenu(ctx);

        savedConfigsList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                DatabaseConfig cfg = savedConfigsList.getSelectionModel().getSelectedItem();
                if (cfg != null) {
                    connectAndOpenPanel(cfg);

                    Stage loginStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
                    loginStage.close();
                }
            }
        });


        ComboBox.setOnAction(e -> {
            String selected = ComboBox.getValue();
            switch (selected) {
                case "MySQL" -> portTextField.setText("3306");
                case "PostgreSQL" -> portTextField.setText("5432");
                case "SqlServer" -> portTextField.setText("1433");
                case "Redis" -> portTextField.setText("6379");
            }
        });
    }

    private void populateFields(DatabaseConfig cfg) {
        ComboBox.setValue(cfg.getDbType());
        hostTextField.setText(cfg.getHost());
        portTextField.setText(cfg.getPort());
        usernameTextField.setText(cfg.getUsername());
        passwordTextField.setText(cfg.getPassword());
        databaseTextField.setText(cfg.getDatabase());
    }


    private void loadPanel(ActionEvent event, controllers ctrl) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/panel.fxml"));
            Parent root = loader.load();

            panel panelCtrl = loader.getController();
            panelCtrl.setControllers(ctrl);

            Stage panelStage = new Stage();
            panelStage.setTitle("Turacos V1.4.1 By: Alanni");
            panelStage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream(("/images/ico.png")))));
            panelStage.setScene(new Scene(root, 900, 600));
            panelStage.show();

            Stage loginStage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            loginStage.close();

        } catch (IOException e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "加载主界面失败!", ButtonType.OK).show();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    private void onComboBoxChanged(ActionEvent event) {
        String dbType = ComboBox.getValue();
        String host = hostTextField.getText();
        String port = portTextField.getText();
        String username = usernameTextField.getText();
        String password = passwordTextField.getText();
        String database = databaseTextField.getText();

        if (testConnection(host, port, database, username, password)) {

            dao.saveOrUpdateConfig(dbType, host, port, username, password, database);

            savedConfigsList.setItems(FXCollections.observableArrayList(dao.getAllConfigs()));

            controllers ctrl = new controllers();
            ctrl.setDatabaseInfo(dbType, host, port, username, password, database);

            loadPanel(event, ctrl);

        } else {
            new Alert(Alert.AlertType.ERROR, "Connection or Open Failed!", ButtonType.OK).show();
        }
    }


    @FXML
    private void onTestconnection() {
        if (testConnection(hostTextField.getText(), portTextField.getText(), databaseTextField.getText(), usernameTextField.getText(), passwordTextField.getText())) {
            new Alert(Alert.AlertType.INFORMATION, "Connection Successful!", ButtonType.OK).show();
        } else {
            new Alert(Alert.AlertType.ERROR, "Connection Failed!", ButtonType.OK).show();
        }
    }


    private boolean testConnection(String host, String port, String database, String username, String password) {
        String type = ComboBox.getValue();
        if ("MySQL".equals(type)) {
            mysql db = new mysql(host, port, database, username, password);
            boolean ok = db.testConnection();
            db.close();
            return ok;
        } else if ("PostgreSQL".equals(type)) {
            psql db = new psql(host, port, database, username, password);
            boolean ok = db.testConnection();
            db.close();
            return ok;
        } else if ("SqlServer".equals(type)) {
            mssql db = new mssql(host, port, database, username, password);
            boolean ok = db.testConnection();
            db.close();
            return ok;
        } else if ("Redis".equals(type)) {
            redis db = new redis(host, port, database, username, password);
            boolean ok = db.testConnection();
            db.close();
            return ok;
        }
        return false;
    }

    private void connectAndOpenPanel(DatabaseConfig cfg) {
        boolean ok;
        if ("MySQL".equals(cfg.getDbType())) {
            mysql testDb = new mysql(cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername(), cfg.getPassword());
            ok = testDb.testConnection();
            testDb.close();
        } else if ("PostgreSQL".equals(cfg.getDbType())) {
            psql testDb = new psql(cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername(), cfg.getPassword());
            ok = testDb.testConnection();
            testDb.close();
        } else if ("SqlServer".equals(cfg.getDbType())) {
            mssql testDb = new mssql(cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername(), cfg.getPassword());
            ok = testDb.testConnection();
            testDb.close();
        } else if ("Redis".equals(cfg.getDbType())) {
            redis testDb = new redis(cfg.getHost(), cfg.getPort(), cfg.getDatabase(), cfg.getUsername(), cfg.getPassword());
            ok = testDb.testConnection();
            testDb.close();
        } else {
            ok = false;
        }
        if (!ok) {
            new Alert(Alert.AlertType.ERROR, "数据库连接失败!").show();
            return;
        }

        controllers ctrl = new controllers();
        ctrl.setDatabaseInfo(cfg.getDbType(), cfg.getHost(), cfg.getPort(), cfg.getUsername(), cfg.getPassword(), cfg.getDatabase());

        loadPanel(ctrl);
    }

    private void loadPanel(controllers ctrl) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/panel.fxml"));
            Stage stage = new Stage();
            stage.setScene(new Scene(loader.load(), 900, 600));
            stage.setTitle("Turacos Ver_1.0 By: Alanni");
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/ico.png"))));
            stage.setResizable(false);
            panel panelCtrl = loader.getController();

            panelCtrl.setControllers(ctrl);

            stage.show();
        } catch (IOException e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "加载主界面失败!").show();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    private void onTogglePasswordVisibility() {
        showPassword.set(!showPassword.get());
        if (togglePasswordImage != null) {
            togglePasswordImage.setImage(showPassword.get() ? eyeOpen : eyeClosed);
        }
    }

    private javafx.scene.image.Image loadImageOrFallback(String primary, String fallback) {
        try {
            java.net.URL p = getClass().getResource(primary);
            if (p != null) {
                return new javafx.scene.image.Image(p.toExternalForm());
            }
        } catch (Exception ignored) {
        }
        try {
            java.net.URL f = getClass().getResource(fallback);
            if (f != null) {
                return new javafx.scene.image.Image(f.toExternalForm());
            }
        } catch (Exception ignored) {
        }
        return null;
    }


}