package ui;

import javafx.scene.Scene;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/**
 * 主题管理器：亮色 / 深色切换，选择持久化到 Preferences。
 *
 * <p>实现方式：{@code main.css}/{@code panel.css} 为“基础 + 亮色主题”，
 * {@code theme-dark.css} 作为深色覆盖层。切换 = 向所有已注册 Scene
 * 添加/移除深色样式表（后添加者覆盖先添加者）。
 */
public final class ThemeManager {

    /** 深色覆盖样式（与 main.css/panel.css 同选择器，后加载覆盖颜色）。 */
    public static final String DARK_CSS = "/style/theme-dark.css";

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(ThemeManager.class);
    private static final String KEY = "darkTheme";

    private static volatile boolean dark = PREFS.getBoolean(KEY, false);
    private static final List<WeakReference<Scene>> SCENES = new CopyOnWriteArrayList<>();

    private ThemeManager() {
    }

    public static boolean isDark() {
        return dark;
    }

    /** 注册窗口并应用当前主题（在 Scene 创建后调用一次）。 */
    public static void apply(Scene scene) {
        SCENES.add(new WeakReference<>(scene));
        sync(scene);
    }

    /** 切换主题并刷新所有已注册窗口，返回新的主题状态（true=深色）。 */
    public static boolean toggle() {
        dark = !dark;
        PREFS.putBoolean(KEY, dark);
        for (WeakReference<Scene> ref : SCENES) {
            Scene s = ref.get();
            if (s != null) {
                sync(s);
            }
        }
        return dark;
    }

    private static void sync(Scene scene) {
        javafx.scene.Parent root = scene.getRoot();
        if (root != null) {
            // 深色覆盖层必须挂在根节点样式表（与 main.css/panel.css 同一列表）
            // 并在其后追加，才能确保覆盖优先级；Scene 级样式会先于父节点样式应用。
            var sheets = root.getStylesheets();
            sheets.remove(DARK_CSS);
            if (dark) {
                sheets.add(DARK_CSS);
            }
        } else {
            scene.getStylesheets().remove(DARK_CSS);
            if (dark) {
                scene.getStylesheets().add(DARK_CSS);
            }
        }
    }
}
