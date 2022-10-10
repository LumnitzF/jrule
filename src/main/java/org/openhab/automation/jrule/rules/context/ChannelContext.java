package org.openhab.automation.jrule.rules.context;

import org.openhab.automation.jrule.items.JRuleItem;

public class ChannelContext extends JRuleContext {

    private final String channel;

    private final String eq;

    private final String neq;

    private ChannelContext(LoggingContext loggingContext, String channel, String eq, String neq) {
        super(loggingContext);
        this.channel = channel;
        this.eq = eq;
        this.neq = neq;
    }

    public static ChannelContext channelEquals(LoggingContext loggingContext, String channel, String eq) {
        return new ChannelContext(loggingContext, channel, eq, null);
    }

    public static ChannelContext channelNotEquals(LoggingContext loggingContext, String channel, String neq) {
        return new ChannelContext(loggingContext, channel, null, neq);
    }

    public JRuleItem getItem() {
        return item;
    }

    public String getChannel() {
        return channel;
    }

    public String getEq() {
        return eq;
    }

    public String getNeq() {
        return neq;
    }

}
