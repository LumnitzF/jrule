package org.openhab.automation.jrule.rules;

import org.openhab.automation.jrule.items.JRuleItem;
import org.openhab.automation.jrule.rules.context.JRuleContext;

import java.lang.reflect.Method;
import java.util.Collection;

public abstract class JRuleDynamicRule extends JRule {

	public abstract Collection<JRuleContext> evaluateExecutionContexts(Method ruleMethod);

}
