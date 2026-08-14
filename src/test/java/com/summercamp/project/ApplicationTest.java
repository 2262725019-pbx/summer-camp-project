package com.summercamp.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ApplicationTest {

    @Test
    void shouldEmitAllSupportedLogLevels() {
        Logger logger = (Logger) LoggerFactory.getLogger(Application.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            Application.main(new String[0]);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        List<Level> levels = appender.list.stream()
                .map(ILoggingEvent::getLevel)
                .toList();

        assertEquals(List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR), levels);
    }
}
