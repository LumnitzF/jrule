/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.jrule.internal.engine;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.jrule.internal.JRuleConfig;
import org.openhab.automation.jrule.internal.JRuleConstants;
import org.openhab.automation.jrule.internal.JRuleLog;
import org.openhab.automation.jrule.internal.JRuleUtil;
import org.openhab.automation.jrule.internal.cron.JRuleCronExpression;
import org.openhab.automation.jrule.internal.events.JRuleEventSubscriber;
import org.openhab.automation.jrule.items.JRuleItem;
import org.openhab.automation.jrule.items.JRuleItemRegistry;
import org.openhab.automation.jrule.rules.JRule;
import org.openhab.automation.jrule.rules.JRuleDynamicRule;
import org.openhab.automation.jrule.rules.JRuleEvent;
import org.openhab.automation.jrule.rules.JRuleLogName;
import org.openhab.automation.jrule.rules.JRuleName;
import org.openhab.automation.jrule.rules.JRulePrecondition;
import org.openhab.automation.jrule.rules.JRuleTag;
import org.openhab.automation.jrule.rules.JRuleWhen;
import org.openhab.automation.jrule.rules.context.ChannelContext;
import org.openhab.automation.jrule.rules.context.ItemContext;
import org.openhab.automation.jrule.rules.context.JRuleContext;
import org.openhab.automation.jrule.rules.context.TimedContext;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.events.Event;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.GroupItemStateChangedEvent;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.items.events.ItemEvent;
import org.openhab.core.items.events.ItemStateChangedEvent;
import org.openhab.core.items.events.ItemStateEvent;
import org.openhab.core.thing.events.ChannelTriggeredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The {@link JRuleConstants} class defines common constants, which are
 * used across the Java Rule automation.
 *
 * @author Joseph (Seaside) Hagberg - Initial contribution
 */
public class JRuleEngine implements PropertyChangeListener {

    private static final String MDC_KEY_RULE = "rule";

    private static final int AWAIT_TERMINATION_THREAD_SECONDS = 2;

    private static final String RECEIVED_COMMAND = "received command";

    private static final String RECEIVED_COMMAND_APPEND = RECEIVED_COMMAND + " ";

    private static final String CHANGED_FROM_TO_PATTERN = "Changed from %1$s to %2$s";

    private static final String CHANGED_FROM = "Changed from ";

    private static final String CHANGED_TO = "Changed to ";

    private static final String CHANGED = "Changed";

    private static final String RECEIVED_UPDATE = "received update";

    private static final String RECEIVED_UPDATE_APPEND = RECEIVED_UPDATE + " ";

    private static final String LOG_NAME_ENGINE = "JRuleEngine";

    private static final String[] EMPTY_LOG_TAGS = new String[0];

    private static volatile JRuleEngine instance;

    private ThreadPoolExecutor ruleExecutorService;

    private JRuleConfig config;

    private final Map<String, List<JRule>> itemToRules = new HashMap<>();

    private final Map<String, List<JRuleExecutionContext>> itemToExecutionContexts = new HashMap<>();

    private final Map<String, List<JRuleExecutionContext>> channelToExecutionContexts = new HashMap<>();

    private final Set<String> itemNames = new HashSet<>();

    private final Logger logger = LoggerFactory.getLogger(JRuleEngine.class);

    private final Set<CompletableFuture<Void>> timers = new HashSet<>();

    protected final ScheduledExecutorService scheduler = ThreadPoolManager
            .getScheduledPool(ThreadPoolManager.THREAD_POOL_NAME_COMMON);

    private ItemRegistry itemRegistry;

    private JRuleLoadingStatistics ruleLoadingStatistics;

    private JRuleEngine() {
        ruleLoadingStatistics = new JRuleLoadingStatistics(null);
    }

    public static JRuleEngine get() {
        if (instance == null) {
            synchronized (JRuleEngine.class) {
                if (instance == null) {
                    instance = new JRuleEngine();
                }
            }
        }
        return instance;
    }

    public Set<String> getItemNames() {
        return itemNames;
    }

    public Set<String> getChannelNames() {
        return channelToExecutionContexts.keySet();
    }

    public synchronized void reset() {
        itemNames.clear();
        itemToExecutionContexts.clear();
        channelToExecutionContexts.clear();
        itemToRules.clear();
        clearTimers();

        ruleLoadingStatistics = new JRuleLoadingStatistics(ruleLoadingStatistics);
    }

    private synchronized void clearTimers() {
        timers.forEach(timer -> timer.cancel(true));
        timers.clear();
    }

    private void logInfo(String message, Object... paramteres) {
        JRuleLog.info(logger, LOG_NAME_ENGINE, message, paramteres);
    }

    private void logDebug(String message, Object... parameters) {
        JRuleLog.debug(logger, LOG_NAME_ENGINE, message, parameters);
    }

    private void logWarn(String message, Object... parameters) {
        JRuleLog.warn(logger, LOG_NAME_ENGINE, message, parameters);
    }

    private void logError(String message, Object... parameters) {
        JRuleLog.error(logger, LOG_NAME_ENGINE, message, parameters);
    }

    public void add(JRule jRule) {
        logDebug("Adding rule: {}", jRule);
        if (jRule instanceof JRuleDynamicRule) {
            addDynamicRule((JRuleDynamicRule) jRule);
        } else {
            addClassicRule(jRule);
        }
        ruleLoadingStatistics.addRuleClass();
    }

    private void addClassicRule(JRule jRule) {
        Class<?> clazz = jRule.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            logDebug("Adding rule method: {}", method.getName());
            if (!method.isAnnotationPresent(JRuleName.class)) {
                logDebug("Rule method ignored since JRuleName annotation is missing: {}", method.getName());
                continue;
            }
            // Check if method is public, else execution will fail at runtime
            boolean isPublic = (method.getModifiers() & Modifier.PUBLIC) != 0;
            if (!isPublic) {
                logWarn("Rule method ignored since method isn't public: {}", method.getName());
                continue;
            }

            if (method.getAnnotationsByType(JRuleWhen.class).length == 0) {
                logWarn("Rule ignored since JWhens annotation is missing");
                logWarn("Rule JWhen present: {}", method.isAnnotationPresent(JRuleWhen.class));
                final JRuleWhen[] jRuleWhens = method.getAnnotationsByType(JRuleWhen.class);
                logDebug("Got jrule whens size: {}", jRuleWhens.length);
                continue;
            }
            final JRuleName jRuleName = method.getDeclaredAnnotation(JRuleName.class);
            final JRuleLogName jRuleLogName = method.isAnnotationPresent(JRuleLogName.class)
                    ? method.getDeclaredAnnotation(JRuleLogName.class)
                    : null;

            final JRulePrecondition[] preconditions = method.getAnnotationsByType(JRulePrecondition.class);

            final JRuleWhen[] jRuleWhens = method.getAnnotationsByType(JRuleWhen.class);
            logDebug("Got jrule whens size: {}", jRuleWhens.length);
            final Parameter[] parameters = method.getParameters();

            String logName = (jRuleLogName != null && !jRuleLogName.value().isEmpty()) ? jRuleLogName.value()
                    : jRuleName.value();

            final JRuleTag jRuleTags = method.isAnnotationPresent(JRuleTag.class)
                    ? method.getDeclaredAnnotation(JRuleTag.class)
                    : null;
            final String[] loggingTags = jRuleTags != null ? jRuleTags.value() : EMPTY_LOG_TAGS;
            // TODO: Do validation on syntax in when annotations
            // Loop for other ORs
            for (JRuleWhen jRuleWhen : jRuleWhens) {
                JRuleLog.debug(logger, logName, "Processing jRule when: {}", jRuleWhen);
                if (!jRuleWhen.item().isEmpty()) {
                    // JRuleWhen for an item
                    handleItemRule(jRule, method, jRuleName, preconditions, logName, loggingTags, jRuleWhen);
                } else if (jRuleWhen.hours() != -1 || jRuleWhen.minutes() != -1 || jRuleWhen.seconds() != -1
                        || !jRuleWhen.cron().isEmpty()) {
                    // JRuleWhen for a time trigger
                    handleTimedRule(jRule, method, jRuleName, preconditions, logName, loggingTags, jRuleWhen);
                } else if (!jRuleWhen.channel().isEmpty()) {
                    // JRuleWhen for a channel
                    handleChannelRule(jRule, method, preconditions, logName, loggingTags, jRuleWhen, jRuleName.value());
                }
            }
            if (jRuleWhens.length > 0) {
                ruleLoadingStatistics.addRuleMethod();
            }

        }
    }

    private void addDynamicRule(JRuleDynamicRule jRule) {
        for (Method method : jRule.getClass().getMethods()) {
            for (JRuleContext ruleContext : jRule.evaluateExecutionContexts(method)) {
                addRule(jRule, method, ruleContext);
            }
        }
    }

    private void addRule(JRuleDynamicRule jRule, Method ruleMethod, JRuleContext ruleContext) {
        if (ruleContext instanceof ChannelContext) {
            ChannelContext channelContext = (ChannelContext) ruleContext;
            handleChannelRule(jRule, ruleMethod, channelContext, new JRulePrecondition[0]);
        } else if (ruleContext instanceof TimedContext) {
            TimedContext timedContext = (TimedContext) ruleContext;
            addTimedExecution(jRule, ruleMethod, timedContext, new JRulePrecondition[0]);
        } else if (ruleContext instanceof ItemContext) {
            ItemContext itemContext = (ItemContext) ruleContext;
            handleItemRule(jRule, ruleMethod, new JRulePrecondition[0], itemContext);
        }
    }

    private void handleChannelRule(JRule jRule, Method method, JRulePrecondition[] preconditions,
                                   String logName, String[] loggingTags,
                                   JRuleWhen jRuleWhen, String ruleName) {
        JRuleLog.info(logger, logName, "Validating JRule channel: {} trigger: {} ", jRuleWhen.channel(),
                jRuleWhen.trigger());
        String channel = jRuleWhen.channel();
        String eq = getStringFromAnnotation(jRuleWhen.eq());
        String neq = getStringFromAnnotation(jRuleWhen.neq());
        JRuleContext.LoggingContext loggingContext = new JRuleContext.LoggingContext(ruleName, logName,
                Arrays.asList(loggingTags));
        ChannelContext channelContext;
        if (eq != null) {
            channelContext = ChannelContext.channelEquals(loggingContext, channel, eq);
        } else if (neq != null) {
            channelContext = ChannelContext.channelNotEquals(loggingContext,
                    channel, neq);
        } else {
            return;
        }
        handleChannelRule(jRule, method, channelContext, preconditions);
    }

    private void handleChannelRule(JRule jRule, Method ruleMethod, ChannelContext channelContext,
                                   JRulePrecondition[] preconditions) {
        handleChannelContext(jRule, ruleMethod, preconditions, channelContext);
        ruleLoadingStatistics.addChannelTrigger();
    }

    private static boolean isEventParameterPresent(Method method) {
        return Arrays.asList(method.getParameterTypes()).contains(JRuleEvent.class);
    }

    private void handleTimedRule(JRule jRule, Method method, JRuleName jRuleName, JRulePrecondition[] preconditions, String logName, String[] loggingTags, JRuleWhen jRuleWhen) {
        JRuleLog.info(logger, logName,
                "Validating JRule: Scheduling timer for hours: {} minutes: {} seconds: {} cron: {}",
                jRuleWhen.hours(), jRuleWhen.minutes(), jRuleWhen.seconds(), jRuleWhen.cron());
        addTimedExecution(jRule, logName, loggingTags, jRuleName.value(), jRuleWhen, method,
                preconditions);
        ruleLoadingStatistics.addTimedTrigger();
    }

    private void handleItemRule(JRule jRule, Method method, JRuleName jRuleName, JRulePrecondition[] preconditions, String logName, String[] loggingTags, JRuleWhen jRuleWhen) {
        JRuleItem item = null;
        try {
            item = getItem(logName, jRuleWhen.item());
        } catch (ClassNotFoundException e) {
            logError("Unable to load item {}", jRuleWhen.item(), e);
        }

        ItemContext itemContext;
        String trigger = jRuleWhen.trigger();
        JRuleContext.LoggingContext loggingContext = new JRuleContext.LoggingContext(jRuleName.value(), logName,
                Arrays.asList(loggingTags));
        if (!jRuleWhen.update().isEmpty()) {
            itemContext = ItemContext.itemUpdated(loggingContext, item, trigger, jRuleWhen.update());
        } else if (!jRuleWhen.from().isEmpty() || !jRuleWhen.to().isEmpty()) {
            itemContext = ItemContext.itemUpdatedFromTo(loggingContext, item, trigger, jRuleWhen.from(), jRuleWhen.to());
        } else if (!jRuleWhen.eq().isEmpty()) {
            itemContext = ItemContext.itemUpdatedToEqual(loggingContext, item, trigger, jRuleWhen.eq());
        } else if (!jRuleWhen.neq().isEmpty()) {
            itemContext = ItemContext.itemUpdatedToNotEqual(loggingContext, item, trigger, jRuleWhen.neq());
        } else if (jRuleWhen.lt() != Double.MIN_VALUE) {
            itemContext = ItemContext.itemUpdatedToLower(loggingContext, item, trigger, jRuleWhen.lt());
        } else if (jRuleWhen.lte() != Double.MIN_VALUE) {
            itemContext = ItemContext.itemUpdatedToLowerOrEqual(loggingContext, item, trigger, jRuleWhen.lte());
        } else if (jRuleWhen.gt() != Double.MIN_VALUE) {
            itemContext = ItemContext.itemUpdatedToGreater(loggingContext, item, trigger, jRuleWhen.gt());
        } else if (jRuleWhen.gte() != Double.MIN_VALUE) {
            itemContext = ItemContext.itemUpdatedToLowerOrEqual(loggingContext, item, trigger, jRuleWhen.gte());
        } else {
            itemContext = ItemContext.itemUpdated(loggingContext, item, trigger);
        }

        handleItemRule(jRule, method, preconditions, itemContext);
    }

    private void handleItemRule(JRule jRule, Method method, JRulePrecondition[] preconditions, ItemContext itemContext) {
        JRuleLog.info(logger, itemContext.getLogName(), "Validating JRule item: {} trigger: {} ", itemContext.getItem().getName(),
                itemContext.getTrigger());
        addExecutionContext(jRule, method, itemContext, preconditions);
        ruleLoadingStatistics.addItemStateTrigger();
    }

    private JRuleItem getItem(String logName, String item) throws ClassNotFoundException {
        String itemPackage = config.getGeneratedItemPackage();
        String prefix = config.getGeneratedItemPrefix();
        String itemClass = String.format("%s.%s%s", itemPackage, prefix, item);
        Class<? extends JRuleItem> jRuleItemClass = Class.forName(itemClass).asSubclass(JRuleItem.class);
        JRuleLog.debug(logger, logName, "Got item class: {}", jRuleItemClass);
        return JRuleItemRegistry.get(item, jRuleItemClass);
    }

    private Double getDoubleFromAnnotation(double d) {
        if (d == Double.MIN_VALUE) {
            return null;
        }
        return d;
    }

    private String getStringFromAnnotation(String s) {
        return s.isEmpty() ? null : s;
    }

    private CompletableFuture<Void> createTimer(TimedContext context) {
        Instant nextExecution = context.evaluateNextExecution(ZonedDateTime.now());
        long initialDelay = Duration.between(Instant.now(), nextExecution).toMillis();
        JRuleLog.debug(logger, context.getLogName(), "Schedule cron: {} initialDelay: {}", nextExecution, initialDelay);
        Executor delayedExecutor = CompletableFuture.delayedExecutor(initialDelay, TimeUnit.MILLISECONDS, scheduler);
        return CompletableFuture.supplyAsync(() -> null, delayedExecutor);
    }

    private synchronized void addTimedExecution(JRule jRule, String logName, String[] loggingTags, String jRuleName,
                                                JRuleWhen jRuleWhen, Method method, JRulePrecondition[] preconditions) {
        JRuleContext.LoggingContext loggingContext = new JRuleContext.LoggingContext(jRuleName, logName,
                Arrays.asList(loggingTags));
        TimedContext context;
        if (jRuleWhen.cron().isEmpty())
            context = TimedContext.dayTimeBasedTimedContext(loggingContext, LocalTime.of(jRuleWhen.hours(),
                    jRuleWhen.minutes(),
                    jRuleWhen.seconds()));
        else {
            final JRuleCronExpression cronExpression;
            try {
                cronExpression = new JRuleCronExpression(jRuleWhen.cron());
            } catch (IllegalArgumentException x) {
                JRuleLog.error(logger, logName, "Failed to parse cron expression for cron: {} message: {}",
                        jRuleWhen.cron(), x.getMessage());
                return;
            }
            context = TimedContext.cronBasedTimedContext(loggingContext, cronExpression);
        }
        addTimedExecution(jRule, method, context, preconditions);
    }

    private synchronized void addTimedExecution(JRule jRule, Method method, TimedContext context,
                                                JRulePrecondition[] preconditions) {
        CompletableFuture<Void> future = createTimer(context);
        boolean jRuleEventPresent = isEventParameterPresent(method);
        timers.add(future);
        JRuleLog.info(logger, context.getLogName(), "Scheduling timer {} for rule: {}", jRule, context);
        JRuleExecutionContext executionContext = new JRuleExecutionContext(jRule, context.getLogName(),
                context.getLogTags().toArray(String[]::new),
                method,
                context.getRuleName(), isEventParameterPresent(method), preconditions);
        Consumer<Void> consumer = t -> {
            try {
                invokeRule(executionContext, jRuleEventPresent ? new JRuleEvent("") : null);
            } finally {
                timers.remove(future);
            }
        };
        future.thenAccept(consumer).thenAccept(s -> {
            JRuleLog.info(logger, context.getLogName(), "Timer has finished");
            addTimedExecution(jRule, method,
                    context, preconditions);
        });
    }

    private void addExecutionContext(JRule jRule, Method ruleMethod, ItemContext itemContext,
                                     JRulePrecondition[] preconditions) {
        List<JRuleExecutionContext> contextList = itemToExecutionContexts.computeIfAbsent(itemContext.getItem().getName(),
                k -> new ArrayList<>());
        final JRuleExecutionContext context = new JRuleExecutionContext(jRule, itemContext.getLogName(),
                itemContext.getLogTags().toArray(new String[0]), itemContext.getTrigger(),
                itemContext.getFrom(), itemContext.getTo(),
                itemContext.getUpdate(), itemContext.getRuleName(), itemContext.getItem().getClass().getName(),
                itemContext.getItem().getName(),
                ruleMethod,
                isEventParameterPresent(ruleMethod), itemContext.getLt(),
                itemContext.getLte(),
                itemContext.getGt(), itemContext.getGte(), itemContext.getEq(),
                itemContext.getNeq(),
                preconditions);
        JRuleLog.debug(logger, itemContext.getLogName(), "ItemContextList add context: {}", context);
        contextList.add(context);
        itemNames.add(itemContext.getItem().getName());
    }

    private void handleChannelContext(JRule jRule, Method method, JRulePrecondition[] preconditions, ChannelContext channelContext) {
        List<JRuleExecutionContext> contextList = channelToExecutionContexts.computeIfAbsent(channelContext.getChannel(),
                k -> new ArrayList<>());
        final JRuleExecutionContext context = new JRuleExecutionContext(jRule, channelContext.getLogName(),
                channelContext.getLogTags().toArray(new String[0]), null,
                null, null,
                null, channelContext.getRuleName(), null, null, method, isEventParameterPresent(method), null, null,
                null, null, channelContext.getEq(), channelContext.getNeq(),
                preconditions);
        JRuleLog.debug(logger, channelContext.getLogName(), "ChannelContextList add context: {}", context);
        contextList.add(context);
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (evt.getPropertyName().equals(JRuleEventSubscriber.PROPERTY_ITEM_EVENT)) {
            logDebug("Property change item event! : {}", ((Event) evt.getNewValue()).getTopic());
            handleEventUpdate((Event) evt.getNewValue());

        } else if (evt.getPropertyName().equals(JRuleEventSubscriber.PROPERTY_CHANNEL_EVENT)) {
            logDebug("Channel event! : {}", ((Event) evt.getNewValue()).getTopic());
            handleChannelEvent((ChannelTriggeredEvent) evt.getNewValue());
        }
    }

    private void handleChannelEvent(ChannelTriggeredEvent channelEvent) {
        List<JRuleExecutionContext> executionContexts = channelToExecutionContexts
                .get(channelEvent.getChannel().toString());
        if (executionContexts == null || executionContexts.isEmpty()) {
            logDebug("No execution context for channelEvent: {}", channelEvent);
            return;
        }
        executionContexts.forEach(context -> invokeWhenMatchParameters(context,
                new JRuleEvent(channelEvent.getEvent(), channelEvent.getChannel().toString())));
    }

    private void handleEventUpdate(Event event) {
        final String itemName = getItemNameFromEvent(event);
        final List<JRuleExecutionContext> executionContexts = itemToExecutionContexts.get(itemName);
        if (executionContexts == null || executionContexts.isEmpty()) {
            logDebug("No execution context for changeEvent ");
            return;
        }
        final String type = event.getType();

        final Set<String> triggerValues = new HashSet<>(5);
        final String stringNewValue;
        final String stringOldValue;
        String memberName = null;
        if (event instanceof GroupItemStateChangedEvent) {
            memberName = ((GroupItemStateChangedEvent) event).getMemberName();
        }

        if (event instanceof ItemStateEvent) {
            stringNewValue = ((ItemStateEvent) event).getItemState().toFullString();
            stringOldValue = null;
            triggerValues.add(RECEIVED_UPDATE);
            triggerValues.add(RECEIVED_UPDATE_APPEND.concat(stringNewValue));
        } else if (event instanceof ItemCommandEvent) {
            stringNewValue = ((ItemCommandEvent) event).getItemCommand().toFullString();
            stringOldValue = null;
            triggerValues.add(RECEIVED_COMMAND);
            triggerValues.add(RECEIVED_COMMAND_APPEND.concat(stringNewValue));
        } else if (event instanceof ItemStateChangedEvent) {
            stringNewValue = ((ItemStateChangedEvent) event).getItemState().toFullString();
            stringOldValue = ((ItemStateChangedEvent) event).getOldItemState().toFullString();

            if (JRuleUtil.isNotEmpty(stringOldValue) && JRuleUtil.isNotEmpty(stringNewValue)) {
                triggerValues.add(String.format(CHANGED_FROM_TO_PATTERN, stringOldValue, stringNewValue));
                triggerValues.add(CHANGED_FROM.concat(stringOldValue));
                triggerValues.add(CHANGED_TO.concat(stringNewValue));
                triggerValues.add(CHANGED);
            }

            logDebug("newValue: {} oldValue: {} type: {}", stringNewValue, stringOldValue, type);
            logDebug("Invoked execution contexts: {}", executionContexts.size());
            logDebug("Execution topic Topic: {}", event.getTopic());
            logDebug("Execution topic Payload: {}", event.getPayload());
            logDebug("Execution topic Source: {}", event.getSource());
            logDebug("Execution topic Type: {}", event.getType());
            logDebug("Execution eventToString: {}", event);
        } else {
            logDebug("Unhandled case: {}", event.getClass());
            return;
        }

        if (!triggerValues.isEmpty()) {
            String member = memberName == null ? "" : memberName;
            executionContexts.stream().filter(context -> triggerValues.contains(context.getTriggerFullString()))
                    .forEach(context -> invokeWhenMatchParameters(context,
                            new JRuleEvent(stringNewValue, stringOldValue, itemName, member)));
        } else {
            logDebug("Execution ignored, no trigger values for itemName: {} eventType: {}", itemName, type);
        }
    }

    private Boolean evaluateComparatorParameters(Double gt, Double gte, Double lt, Double lte, String eq, String neq,
                                                 String stateValue) {
        if (eq != null) {
            return stateValue.equals(eq);
        } else if (neq != null) {
            return !stateValue.equals(neq);
        } else {
            // valueAsDouble may be null if unparseable ("NULL" or "UNDEF")
            Double valueAsDouble = getValueAsDouble(stateValue);
            if (valueAsDouble == null) {
                return null;
            } else if (gt != null) {
                return valueAsDouble > gt;
            } else if (gte != null) {
                return valueAsDouble >= gte;
            } else if (lt != null) {
                return valueAsDouble < lt;
            } else if (lte != null) {
                return valueAsDouble <= lte;
            }
        }
        return null;
    }

    private void invokeWhenMatchParameters(JRuleExecutionContext context, @NonNull JRuleEvent jRuleEvent) {
        JRuleLog.debug(logger, context.getLogName(), "invoke when context matches");

        if (context.isComparatorOperation()) {
            final Boolean evalCompare = evaluateComparatorParameters(context.getGt(), context.getGte(), context.getLt(),
                    context.getLte(), context.getEq(), context.getNeq(), jRuleEvent.getState().getValue());
            if (evalCompare == null) {
                logError("Failed to compare values for context: {} event: {}", context, jRuleEvent);
                return;
            }
            if (!evalCompare) {
                logDebug("Not invoking rule since comparator compare is false context: {} event: {}", context,
                        jRuleEvent);
                return;
            }
        }
        invokeRule(context, jRuleEvent);
    }

    private Double getValueAsDouble(String value) {
        double parseDouble = 0;
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            parseDouble = Double.parseDouble(value);
        } catch (Exception x) {
            logError("Failed to parse value: {} as double", value, x);
            return null;
        }
        return parseDouble;
    }

    @Nullable
    private String getItemNameFromEvent(Event event) {
        if (event instanceof ItemEvent) {
            return ((ItemEvent) event).getItemName();
        }
        return null;
    }

    private void invokeRule(JRuleExecutionContext context, JRuleEvent event) {
        Object invokationResult = config.isExecutorsEnabled() ? invokeRuleInSeparateThread(context, event)
                : invokeRuleSingleThread(context, event);
    }

    private Object invokeRuleInSeparateThread(JRuleExecutionContext context, JRuleEvent event) {
        return ruleExecutorService.submit(() -> invokeRuleInternal(context, event));
    }

    private synchronized Object invokeRuleSingleThread(JRuleExecutionContext context, JRuleEvent event) {
        return invokeRuleInternal(context, event);
    }

    private Object invokeRuleInternal(JRuleExecutionContext context, JRuleEvent event) {
        JRuleLog.debug(logger, context.getLogName(), "Invoking rule for context: {}", context);

        // Check preconditions
        boolean preconditionsSatisified = true;
        JRulePrecondition[] preconditions = context.getPreconditions();
        if (preconditions != null) {
            for (JRulePrecondition precondition : preconditions) {
                preconditionsSatisified &= evaluatePrecondition(context, precondition);
            }
        }

        if (preconditionsSatisified) {
            final JRule rule = context.getJrule();
            final Method method = context.getMethod();
            rule.setRuleLogName(context.getLogName());
            try {
                JRuleLog.debug(logger, context.getRuleName(), "setting mdc tags: {}", context.getLoggingTags());
                MDC.put(MDC_KEY_RULE, context.getRuleName());
                Arrays.stream(context.getLoggingTags()).forEach(s -> MDC.put(s, s));
                return context.isEventParameterPresent() ? method.invoke(rule, event) : method.invoke(rule);
            } catch (IllegalAccessException | IllegalArgumentException | SecurityException e) {
                JRuleLog.error(logger, context.getRuleName(), "Error {}", e);
            } catch (InvocationTargetException e) {
                Throwable ex = e.getCause() != null ? e.getCause() : null;
                JRuleLog.error(logger, context.getRuleName(), "Error message: {}", ex.getMessage());
                JRuleLog.error(logger, context.getRuleName(), "Error Stacktrace: {}", getStackTraceAsString(ex));
            } finally {
                Arrays.stream(context.getLoggingTags()).forEach(MDC::remove);
                MDC.remove(MDC_KEY_RULE);
            }
        } else {
            JRuleLog.debug(logger, context.getLogName(), "Preconditions failed for context: {}", context);
        }
        return null;
    }

    private boolean evaluatePrecondition(JRuleExecutionContext context, JRulePrecondition precondition) {
        try {
            final Item item = itemRegistry.getItem(precondition.item());
            final String state = item.getState().toString();
            Boolean evalComparatorParams = evaluateComparatorParameters(getDoubleFromAnnotation(precondition.gt()),
                    getDoubleFromAnnotation(precondition.gte()), getDoubleFromAnnotation(precondition.lt()),
                    getDoubleFromAnnotation(precondition.lte()), getStringFromAnnotation(precondition.eq()),
                    getStringFromAnnotation(precondition.neq()), state);
            if (evalComparatorParams == null) {
                logError("Failed to evaluate precondition context: {} precondition: {} state: {}", context,
                        toString(precondition), state);
            }
            return evalComparatorParams == null || evalComparatorParams;
        } catch (ItemNotFoundException e) {
            JRuleLog.error(logger, context.getRuleName(), "Precondition item not found: {}", precondition.item());
        }
        return true; // For now
    }

    private String toString(JRulePrecondition precondition) {
        return "item=" + precondition.item() + " eq=" + precondition.eq() + " neq=" + precondition.neq() + " gt="
                + precondition.gt() + " gte=" + precondition.gte() + " lt=" + precondition.lt() + " lte="
                + precondition.lte();
    }

    private synchronized static String getStackTraceAsString(Throwable throwable) {
        try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
            return sw.toString();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    public void setConfig(@NonNull JRuleConfig config) {
        this.config = config;
    }

    public void initialize() {
        if (config.isExecutorsEnabled()) {
            logInfo("Initializing Java Rule Engine with Separate Thread Executors min: {} max: {}",
                    config.getMinExecutors(), config.getMaxExecutors());
            final ThreadFactory ruleExecutorThreadFactory = new ThreadFactory() {
                private final AtomicLong threadIndex = new AtomicLong(0);

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable);
                    thread.setName("JRule-Executor-" + threadIndex.getAndIncrement());
                    return thread;
                }
            };

            // Keep unused threads for 2 minutes before scaling back
            ruleExecutorService = new ThreadPoolExecutor(config.getMinExecutors(), config.getMaxExecutors(),
                    config.getKeepAliveExecutors(), TimeUnit.MINUTES, new LinkedBlockingQueue<>(),
                    ruleExecutorThreadFactory);
        } else {
            logInfo("Initializing Java Rule Engine with Single Thread Execution");
        }
    }

    public void dispose() {
        if (config.isExecutorsEnabled()) {
            ruleExecutorService.shutdownNow();
            try {
                ruleExecutorService.awaitTermination(AWAIT_TERMINATION_THREAD_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logWarn("Not all rules ran to completion before rule engine shutdown", e);
            }
        }
    }

    public void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    public JRuleLoadingStatistics getRuleLoadingStatistics() {
        return ruleLoadingStatistics;
    }

}
