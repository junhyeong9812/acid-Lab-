package com.experiment.acidlab.global.logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 스레드별 색상을 지원하는 콘솔 로거
 */
public class ConsoleLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ANSI 색상 코드
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";

    private static final String[] COLORS = {BLUE, GREEN, PURPLE, CYAN, YELLOW};

    private final String prefix;
    private final boolean useColor;

    public ConsoleLogger(String prefix) {
        this(prefix, true);
    }

    public ConsoleLogger(String prefix, boolean useColor) {
        this.prefix = prefix;
        this.useColor = useColor;
    }

    public void info(String message) {
        log("INFO", message, getThreadColor());
    }

    public void success(String message) {
        log("SUCCESS", message, GREEN);
    }

    public void warn(String message) {
        log("WARN", message, YELLOW);
    }

    public void error(String message) {
        log("ERROR", message, RED);
    }

    public void debug(String message) {
        log("DEBUG", message, CYAN);
    }

    private void log(String level, String message, String color) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String threadName = Thread.currentThread().getName();

        String formattedMessage;
        if (useColor) {
            formattedMessage = String.format("%s[%s] [%s] [%s] %s%s",
                    color, timestamp, threadName, prefix, message, RESET);
        } else {
            formattedMessage = String.format("[%s] [%s] [%s] %s",
                    timestamp, threadName, prefix, message);
        }

        System.out.println(formattedMessage);
    }

    private String getThreadColor() {
        int hash = Math.abs(Thread.currentThread().getName().hashCode());
        return COLORS[hash % COLORS.length];
    }

    // 정적 팩토리 메서드
    public static ConsoleLogger of(String prefix) {
        return new ConsoleLogger(prefix);
    }

    public static ConsoleLogger of(Class<?> clazz) {
        return new ConsoleLogger(clazz.getSimpleName());
    }
}