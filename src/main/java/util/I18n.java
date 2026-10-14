package util;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 轻量国际化工具（骨架）。
 *
 * <p>资源文件：{@code i18n/messages.properties}（默认中文）与
 * {@code i18n/messages_en.properties}（英文）。
 * 新功能文案优先走 {@link #t(String, Object...)}，后续可逐步迁移存量文案。
 */
public final class I18n {

    private static final Logger LOG = Logger.getLogger(I18n.class.getName());
    private static final String BASE = "i18n/messages";
    private static volatile ResourceBundle bundle = load();

    private I18n() {
    }

    /** 取当前语言文案；缺失时原样返回 key。 */
    public static String t(String key, Object... args) {
        try {
            String msg = bundle.getString(key);
            return args == null || args.length == 0 ? msg : MessageFormat.format(msg, args);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /** 切换语言（Locale.ENGLISH / Locale.SIMPLIFIED_CHINESE）并重载。 */
    public static void setLocale(Locale locale) {
        Locale.setDefault(locale);
        bundle = load();
    }

    private static ResourceBundle load() {
        try {
            return ResourceBundle.getBundle(BASE, Locale.getDefault());
        } catch (MissingResourceException e) {
            LOG.log(Level.WARNING, "[!] 未找到语言资源: " + BASE, e);
            return ResourceBundle.getBundle(BASE, Locale.SIMPLIFIED_CHINESE);
        }
    }
}
