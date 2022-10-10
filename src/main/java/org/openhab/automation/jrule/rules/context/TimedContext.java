package org.openhab.automation.jrule.rules.context;

import org.openhab.automation.jrule.internal.cron.JRuleCronExpression;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

public abstract class TimedContext extends JRuleContext {

    private TimedContext(LoggingContext loggingContext) {
        super(loggingContext);
    }

    public static TimedContext cronBasedTimedContext(LoggingContext loggingContext, JRuleCronExpression cron) {
        return new CronBasedTimedContext(loggingContext, cron);
    }

    public static TimedContext dayTimeBasedTimedContext(LoggingContext loggingContext, LocalTime executionTime) {
        return new LocalTimeBasedTimedContext(loggingContext, executionTime);
    }

    public abstract Instant evaluateNextExecution(ZonedDateTime now);

    static class LocalTimeBasedTimedContext extends org.openhab.automation.jrule.rules.context.TimedContext {

        private final LocalTime executionTime;

        LocalTimeBasedTimedContext(LoggingContext loggingContext, LocalTime executionTime) {
            super(loggingContext);
            this.executionTime = executionTime;
        }

        @Override
        public Instant evaluateNextExecution(ZonedDateTime now) {
            if (now.toLocalTime().isBefore(executionTime)) {
                return now.with(executionTime).toInstant();
            } else {
                return now.plusDays(1).with(executionTime).toInstant();
            }
        }

    }

    static class CronBasedTimedContext extends org.openhab.automation.jrule.rules.context.TimedContext {

        private final JRuleCronExpression cronExpression;

        CronBasedTimedContext(LoggingContext loggingContext, JRuleCronExpression cronExpression) {
            super(loggingContext);
            this.cronExpression = cronExpression;
        }

        @Override
        public Instant evaluateNextExecution(ZonedDateTime now) {
            return cronExpression.nextTimeAfter(now).toInstant();
        }

    }

}
