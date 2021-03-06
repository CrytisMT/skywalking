/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.spring.mvc.commons.interceptor;

import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.util.CollectionUtil;
import org.apache.skywalking.apm.agent.core.util.MethodUtil;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.spring.mvc.commons.EnhanceRequireObjectCache;
import org.apache.skywalking.apm.plugin.spring.mvc.commons.SpringMVCPluginConfig;
import org.apache.skywalking.apm.plugin.spring.mvc.commons.exception.IllegalMethodStackDepthException;
import org.apache.skywalking.apm.plugin.spring.mvc.commons.exception.ServletResponseNotFoundException;
import org.apache.skywalking.apm.util.StringUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.skywalking.apm.plugin.spring.mvc.commons.Constants.*;

/**
 * the abstract method interceptor
 */
public abstract class AbstractMethodInterceptor implements InstanceMethodsAroundInterceptor {

    private static boolean IS_SERVLET_GET_STATUS_METHOD_EXIST;
    private static final String SERVLET_RESPONSE_CLASS = "javax.servlet.http.HttpServletResponse";
    private static final String GET_STATUS_METHOD = "getStatus";

    static {
        IS_SERVLET_GET_STATUS_METHOD_EXIST = MethodUtil.isMethodExist(
                AbstractMethodInterceptor.class.getClassLoader(), SERVLET_RESPONSE_CLASS, GET_STATUS_METHOD);
    }

    public abstract String getRequestURL(Method method);

    public abstract String getAcceptedMethodTypes(Method method);

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {

        Boolean forwardRequestFlag = (Boolean) ContextManager.getRuntimeContext().get(FORWARD_REQUEST_FLAG);
        /**
         * Spring MVC plugin do nothing if current request is forward request.
         * Ref: https://github.com/apache/skywalking/pull/1325
         */
        if (forwardRequestFlag != null && forwardRequestFlag) {
            return;
        }

        String operationName;
        if (SpringMVCPluginConfig.Plugin.SpringMVC.USE_QUALIFIED_NAME_AS_ENDPOINT_NAME) {
            operationName = MethodUtil.generateOperationName(method);
        } else {
            EnhanceRequireObjectCache pathMappingCache = (EnhanceRequireObjectCache) objInst.getSkyWalkingDynamicField();
            String requestURL = pathMappingCache.findPathMapping(method);
            if (requestURL == null) {
                requestURL = getRequestURL(method);
                pathMappingCache.addPathMapping(method, requestURL);
                requestURL = pathMappingCache.findPathMapping(method);
            }
            operationName = getAcceptedMethodTypes(method) + requestURL;
        }

        HttpServletRequest request = (HttpServletRequest) ContextManager.getRuntimeContext()
                .get(REQUEST_KEY_IN_RUNTIME_CONTEXT);
        if (request != null) {
            StackDepth stackDepth = (StackDepth) ContextManager.getRuntimeContext().get(CONTROLLER_METHOD_STACK_DEPTH);

            if (stackDepth == null) {
                ContextCarrier contextCarrier = new ContextCarrier();
                CarrierItem next = contextCarrier.items();
                while (next.hasNext()) {
                    next = next.next();
                    next.setHeadValue(request.getHeader(next.getHeadKey()));
                }

                AbstractSpan span = ContextManager.createEntrySpan(operationName, contextCarrier);
                Tags.URL.set(span, request.getRequestURL().toString());
                Tags.HTTP.METHOD.set(span, request.getMethod());
                span.setComponent(ComponentsDefine.SPRING_MVC_ANNOTATION);
                SpanLayer.asHttp(span);

                if (SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS) {
                    collectHttpParam(request, span);
                }

                if (!CollectionUtil.isEmpty(SpringMVCPluginConfig.Plugin.Http.INCLUDE_HTTP_HEADERS)) {
                    collectHttpHeaders(request, span);
                }

                stackDepth = new StackDepth();
                ContextManager.getRuntimeContext().put(CONTROLLER_METHOD_STACK_DEPTH, stackDepth);
            } else {
                AbstractSpan span = ContextManager.createLocalSpan(buildOperationName(objInst, method));
                span.setComponent(ComponentsDefine.SPRING_MVC_ANNOTATION);
            }

            stackDepth.increment();
        }
    }

    private String buildOperationName(Object invoker, Method method) {
        StringBuilder operationName = new StringBuilder(invoker.getClass().getName()).append(".")
                .append(method.getName())
                .append("(");
        for (Class<?> type : method.getParameterTypes()) {
            operationName.append(type.getName()).append(",");
        }

        if (method.getParameterTypes().length > 0) {
            operationName = operationName.deleteCharAt(operationName.length() - 1);
        }

        return operationName.append(")").toString();
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        Boolean forwardRequestFlag = (Boolean) ContextManager.getRuntimeContext().get(FORWARD_REQUEST_FLAG);
        /**
         * Spring MVC plugin do nothing if current request is forward request.
         * Ref: https://github.com/apache/skywalking/pull/1325
         */
        if (forwardRequestFlag != null && forwardRequestFlag) {
            return ret;
        }

        HttpServletRequest request = (HttpServletRequest) ContextManager.getRuntimeContext()
                .get(REQUEST_KEY_IN_RUNTIME_CONTEXT);

        if (request != null) {
            StackDepth stackDepth = (StackDepth) ContextManager.getRuntimeContext().get(CONTROLLER_METHOD_STACK_DEPTH);
            if (stackDepth == null) {
                throw new IllegalMethodStackDepthException();
            } else {
                stackDepth.decrement();
            }

            AbstractSpan span = ContextManager.activeSpan();

            if (stackDepth.depth() == 0) {
                HttpServletResponse response = (HttpServletResponse) ContextManager.getRuntimeContext()
                        .get(
                                RESPONSE_KEY_IN_RUNTIME_CONTEXT);
                if (response == null) {
                    throw new ServletResponseNotFoundException();
                }
                addTraceId(response);

                if (IS_SERVLET_GET_STATUS_METHOD_EXIST && response.getStatus() >= 400) {
                    span.errorOccurred();
                    Tags.STATUS_CODE.set(span, Integer.toString(response.getStatus()));
                }

                ContextManager.getRuntimeContext().remove(REQUEST_KEY_IN_RUNTIME_CONTEXT);
                ContextManager.getRuntimeContext().remove(RESPONSE_KEY_IN_RUNTIME_CONTEXT);
                ContextManager.getRuntimeContext().remove(CONTROLLER_METHOD_STACK_DEPTH);
            }

            // Active HTTP parameter collection automatically in the profiling context.
            if (!SpringMVCPluginConfig.Plugin.SpringMVC.COLLECT_HTTP_PARAMS && span.isProfiling()) {
                collectHttpParam(request, span);
            }

            ContextManager.stopSpan();
        }

        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        ContextManager.activeSpan().errorOccurred().log(t);
    }

    private void collectHttpParam(HttpServletRequest request, AbstractSpan span) {
        final Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap != null && !parameterMap.isEmpty()) {
            String tagValue = CollectionUtil.toString(parameterMap);
            tagValue = SpringMVCPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD > 0 ?
                    StringUtil.cut(tagValue, SpringMVCPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD) : tagValue;
            Tags.HTTP.PARAMS.set(span, tagValue);
        }


        //collect request body
        try {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                String body = request.getReader().lines()
                        .collect(Collectors.joining("\n"));
                Tags.HTTP.BODY.set(span, body);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void addTraceId(HttpServletResponse response) {
        String traceId = ContextManager.getGlobalTraceId();
        response.addHeader("X-TraceId", traceId);
    }

    private void collectHttpHeaders(HttpServletRequest request, AbstractSpan span) {
        final List<String> headersList = new LinkedList<>();
        SpringMVCPluginConfig.Plugin.Http.INCLUDE_HTTP_HEADERS.stream().filter(headerName -> request.getHeaders(headerName) != null).forEach(headerName -> {
            Enumeration<String> headerValues = request.getHeaders(headerName);
            List<String> valueList = Collections.list(headerValues);
            if (!CollectionUtil.isEmpty(valueList)) {
                String headerValue = valueList.toString();
                headersList.add(headerName + "=" + headerValue);
            }
        });

        if (!headersList.isEmpty()) {
            String tagValue = String.join("\n", headersList);
            tagValue = SpringMVCPluginConfig.Plugin.Http.HTTP_HEADERS_LENGTH_THRESHOLD > 0 ?
                    StringUtil.cut(tagValue, SpringMVCPluginConfig.Plugin.Http.HTTP_HEADERS_LENGTH_THRESHOLD) : tagValue;
            Tags.HTTP.HEADERS.set(span, tagValue);
        }
    }
}
