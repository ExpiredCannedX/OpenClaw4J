package com.quashy.openclaw4j.reminder;

/**
 * 定义 reminder V1 在本地调度闭环中允许出现的有限状态集合，避免仓储与调度器散落字符串常量。
 */
public enum ReminderStatus {

    /**
     * 表示提醒已持久化且仍等待未来某个时间点被 heartbeat 扫描。
     */
    SCHEDULED("scheduled"),

    /**
     * 表示提醒已被当前健康进程 claim，正在尝试解析目标并执行一次实际投递。
     */
    DISPATCHING("dispatching"),

    /**
     * 表示提醒已经成功投递到目标渠道，不再参与任何后续自动扫描。
     */
    DELIVERED("delivered"),

    /**
     * 表示提醒已经耗尽自动重试预算，进入需要人工或后续 change 才可能处理的终态。
     */
    FAILED("failed");

    /**
     * 保存写入 SQLite 的稳定状态值，确保仓储、测试和未来迁移脚本共用同一编码。
     */
    private final String databaseValue;

    /**
     * 通过显式数据库值构造枚举，避免持久化层直接依赖 `name()` 这种可读性较差的序列化策略。
     */
    ReminderStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /**
     * 返回当前状态写入 SQLite 时使用的稳定字符串表示。
     */
    public String databaseValue() {
        return databaseValue;
    }

    /**
     * 把 SQLite 中读取的状态值恢复为领域枚举，遇到未知值时直接失败以暴露 schema 或数据污染问题。
     */
    public static ReminderStatus fromDatabaseValue(String databaseValue) {
        for (ReminderStatus status : values()) {
            if (status.databaseValue.equals(databaseValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported reminder status: " + databaseValue);
    }
}
