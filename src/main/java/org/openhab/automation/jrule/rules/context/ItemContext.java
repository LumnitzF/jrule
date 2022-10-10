package org.openhab.automation.jrule.rules.context;

import org.openhab.automation.jrule.items.JRuleItem;

public class ItemContext extends JRuleContext {

	private final JRuleItem item;

	private final String trigger;

	private final String update;

	private final String from;

	private final String to;

	private final Double gt;

	private final Double lt;

	private final Double gte;

	private final Double lte;

	private final String eq;

	private final String neq;

	ItemContext(LoggingContext loggingContext, JRuleItem item, String trigger, String update, String from, String to,
	            Double gt, Double lt, Double gte, Double lte, String eq, String neq) {
		super(loggingContext);
		this.item = item;
		this.trigger = trigger;
		this.update = update;
		this.from = from;
		this.to = to;
		this.gt = gt;
		this.lt = lt;
		this.gte = gte;
		this.lte = lte;
		this.eq = eq;
		this.neq = neq;
	}

	public static ItemContext itemUpdated(LoggingContext loggingContext, JRuleItem item) {
	    return itemUpdated(loggingContext, item, null);
	}

	public static ItemContext itemUpdated(LoggingContext loggingContext, JRuleItem item, String trigger) {
	    return itemUpdated(loggingContext, item, trigger, null);
	}

	public static ItemContext itemUpdated(LoggingContext loggingContext, JRuleItem item, String trigger, String update) {
	    return new ItemContext(loggingContext, item, trigger, update, null, null, null, null, null, null, null, null);
	}

	public static ItemContext itemUpdatedFrom(LoggingContext loggingContext, JRuleItem item, String trigger, String from) {
	    return itemUpdatedFromTo(loggingContext, item, trigger, from, null);
	}

	public static ItemContext itemUpdatedTo(LoggingContext loggingContext, JRuleItem item, String trigger, String to) {
	    return itemUpdatedFromTo(loggingContext, item, trigger, null, to);
	}

	public static ItemContext itemUpdatedFromTo(LoggingContext loggingContext, JRuleItem item, String trigger, String from, String to) {
	    return new ItemContext(loggingContext, item, trigger, null, from, to, null, null, null, null, null, null);
	}

	public static ItemContext itemUpdatedToLower(LoggingContext loggingContext, JRuleItem item, String trigger, double exclusiveUpperBound) {
	    return new ItemContext(loggingContext, item, trigger, null, null, null, null, exclusiveUpperBound, null, null,
	            null, null);
	}

	public static ItemContext itemUpdatedToLowerOrEqual(LoggingContext loggingContext, JRuleItem item, String trigger, double inclusiveUpperBound) {
	    return new ItemContext(loggingContext, item, trigger, null, null, null, null, null, null, inclusiveUpperBound, null, null);
	}

	public static ItemContext itemUpdatedToGreater(LoggingContext loggingContext, JRuleItem item, String trigger, double exclusiveLowerBound) {
	    return new ItemContext(loggingContext, item, trigger, null, null, null, exclusiveLowerBound, null, null, null, null, null);
	}

	public static ItemContext itemUpdatedToGreaterOrEqual
	        (LoggingContext loggingContext, JRuleItem item, String trigger, double inclusiveLowerBound) {
	    return new ItemContext(loggingContext, item, trigger, null, null, null, null, null, inclusiveLowerBound, null, null, null);
	}

	public static ItemContext itemUpdatedToEqual(LoggingContext loggingContext, JRuleItem item, String trigger, String value) {
	    return new ItemContext(loggingContext, item, trigger, null, null, null, null, null, null, null, value, null);
	}

	public static ItemContext itemUpdatedToNotEqual(LoggingContext loggingContext, JRuleItem item, String trigger, String value) {
	    return new ItemContext(loggingContext, item, trigger, null, null, null, null, null, null, null, null, value);
	}

	public JRuleItem getItem() {
		return item;
	}

	public String getTrigger() {
		return trigger;
	}

	public String getUpdate() {
		return update;
	}

	public String getFrom() {
		return from;
	}

	public String getTo() {
		return to;
	}

	public Double getGt() {
		return gt;
	}

	public Double getLt() {
		return lt;
	}

	public Double getGte() {
		return gte;
	}

	public Double getLte() {
		return lte;
	}

	public String getEq() {
		return eq;
	}

	public String getNeq() {
		return neq;
	}

}
