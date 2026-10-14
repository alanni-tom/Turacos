package compliance;

/** 检查项严重级别。 */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    public String label() {
        switch (this) {
            case HIGH: return "高危";
            case MEDIUM: return "中危";
            case LOW: return "低危";
            default: return "信息";
        }
    }

    public String cssClass() {
        return name().toLowerCase();
    }
}
