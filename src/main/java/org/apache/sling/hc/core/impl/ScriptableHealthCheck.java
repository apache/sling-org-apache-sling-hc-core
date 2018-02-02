/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.sling.hc.core.impl;
import java.util.HashSet;
import java.util.Set;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.apache.sling.hc.api.HealthCheck;
import org.apache.sling.hc.api.Result;
import org.apache.sling.hc.util.FormattingResultLog;
import org.apache.sling.scripting.api.BindingsValuesProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link HealthCheck} that checks a scriptable expression */
@Component(
    service = HealthCheck.class,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(
    ocd = ScriptableHealthCheckConfiguration.class,
    factory = true
)
public class ScriptableHealthCheck implements HealthCheck {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private String expression;
    private String languageExtension;

    @Reference
    private ScriptEngineManager scriptEngineManager;

    @Reference(
        cardinality = ReferenceCardinality.MULTIPLE,
        policy = ReferencePolicy.DYNAMIC,
        service = BindingsValuesProvider.class,
        target = "(context=healthcheck)"
    )
    private final Set<BindingsValuesProvider> bindingsValuesProviders = new HashSet<BindingsValuesProvider>();

    @Activate
    protected void activate(final ScriptableHealthCheckConfiguration configuration) {
        expression = configuration.expression();
        languageExtension = configuration.language_extension();

        log.debug("Activated scriptable health check name={}, languageExtension={}, expression={}",
                new Object[] {configuration.hc_name(),
                languageExtension, expression});
    }

    @Override
    public Result execute() {
        final FormattingResultLog resultLog = new FormattingResultLog();
        resultLog.debug("Checking expression [{}], language extension=[{}]",  expression, languageExtension);
        try {
            final ScriptEngine engine = scriptEngineManager.getEngineByExtension(languageExtension);
            if (engine == null) {
                resultLog.healthCheckError("No ScriptEngine available for extension {}", languageExtension);
            } else {
                // Set Bindings, with our ResultLog as a binding first, so that other bindings can use it
                final Bindings b = engine.createBindings();
                b.put(FormattingResultLog.class.getName(), resultLog);
                synchronized (bindingsValuesProviders) {
                    for(BindingsValuesProvider bvp : bindingsValuesProviders) {
                        log.debug("Adding Bindings provided by {}", bvp);
                        bvp.addBindings(b);
                    }
                }
                log.debug("All Bindings added: {}", b.keySet());

                final Object value = engine.eval(expression, b);
                if(value!=null && "true".equals(value.toString().toLowerCase())) {
                    resultLog.debug("Expression [{}] evaluates to true as expected", expression);
                } else {
                    resultLog.warn("Expression [{}] does not evaluate to true as expected, value=[{}]", expression, value);
                }
            }
        } catch (final Exception e) {
            resultLog.healthCheckError(
                    "Exception while evaluating expression [{}] with language extension [{}]: {}",
                    expression, languageExtension, e);
        }
        return new Result(resultLog);
    }

    public void bindBindingsValuesProvider(BindingsValuesProvider bvp) {
        synchronized (bindingsValuesProviders) {
            bindingsValuesProviders.add(bvp);
        }
        log.debug("{} registered: {}", bvp, bindingsValuesProviders);
    }

    public void unbindBindingsValuesProvider(BindingsValuesProvider bvp) {
        synchronized (bindingsValuesProviders) {
            bindingsValuesProviders.remove(bvp);
        }
        log.debug("{} unregistered: {}", bvp, bindingsValuesProviders);
    }
}
