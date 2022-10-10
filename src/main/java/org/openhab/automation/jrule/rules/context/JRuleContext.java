package org.openhab.automation.jrule.rules.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JRuleContext {

    private final LoggingContext loggingContext;

    JRuleContext(LoggingContext loggingContext) {
        this.loggingContext = loggingContext;
    }

    public String getRuleName() {
        return loggingContext.ruleName;
    }

    public String getLogName() {
        return loggingContext.logName;
    }

    public List<String> getLogTags() {
        return Collections.unmodifiableList(loggingContext.logTags);
    }

    public static class LoggingContext {

        private final String ruleName;

        private final String logName;

        private final List<String> logTags;

        public LoggingContext(String ruleName, String logName, List<String> logTags) {
            this.ruleName = ruleName;
            this.logName = logName;
            this.logTags = new ArrayList<>(logTags);
        }


    }

}
