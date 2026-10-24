package com.smashprofs.web.log;
public final class LogManager {
    public static Logger getLogger(Class<?> type) { return new Logger(type.getSimpleName()); }
}
