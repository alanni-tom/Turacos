package ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 开发辅助工具：离屏渲染界面并导出 PNG（不显示窗口），用于 GUI 视觉校验。
 *
 * 用法（对打包后的 fat jar）：
 *   java -cp Turacos-1.0-SNAPSHOT.jar ui.GuiSnapshot --login  login.png
 *   java -cp Turacos-1.0-SNAPSHOT.jar ui.GuiSnapshot --panel  panel.png
 */
public final class GuiSnapshot {

    private GuiSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--login";
        String outFile = args.length > 1 ? args[1] : "gui.png";
        final boolean forceDark = java.util.Arrays.asList(args).contains("--dark");
        double w = "--panel".equals(mode) ? 980 : 740;
        double h = "--panel".equals(mode) ? 640 : 470;

        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        Platform.startup(() -> {
        });

        Platform.runLater(() -> {
            try {
                String fxml = "--panel".equals(mode) ? "/fxml/panel.fxml" : "/fxml/MainUi.fxml";
                FXMLLoader loader = new FXMLLoader(GuiSnapshot.class.getResource(fxml));
                Parent root = loader.load();
                if ("--panel".equals(mode)) {
                    ((panel) loader.getController()).setControllers(null);
                }
                Scene scene = new Scene(root, w, h);
                if (forceDark) {
                    root.getStylesheets().add(ThemeManager.DARK_CSS);
                }
                root.applyCss();
                root.layout();
                WritableImage img = scene.snapshot(null);

                int iw = (int) img.getWidth();
                int ih = (int) img.getHeight();
                BufferedImage bi = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
                PixelReader pr = img.getPixelReader();
                for (int y = 0; y < ih; y++) {
                    for (int x = 0; x < iw; x++) {
                        bi.setRGB(x, y, pr.getArgb(x, y));
                    }
                }
                ImageIO.write(bi, "png", new File(outFile));
                System.out.println("[+] 已保存: " + outFile);
            } catch (Exception e) {
                error.set(e);
            } finally {
                ready.countDown();
            }
        });

        ready.await();
        if (error.get() != null) {
            error.get().printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }
}
