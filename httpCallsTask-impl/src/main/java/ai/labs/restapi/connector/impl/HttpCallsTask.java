package ai.labs.restapi.connector.impl;

import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.resources.rest.http.model.*;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.templateengine.IMemoryTemplateConverter;
import ai.labs.templateengine.ITemplatingEngine;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class HttpCallsTask implements ILifecycleTask {
    private static final String ID = "ai.labs.httpcalls";
    private static final String UTF_8 = "utf-8";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String ACTION_KEY = "actions";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private final IHttpClient httpClient;
    private IJsonSerialization jsonSerialization;
    private final IResourceClientLibrary resourceClientLibrary;
    private IDataFactory dataFactory;
    private final ITemplatingEngine templatingEngine;
    private final IMemoryTemplateConverter memoryTemplateConverter;
    private String targetServerUri;
    private List<HttpCall> httpCalls;

    @Inject
    public HttpCallsTask(IHttpClient httpClient, IJsonSerialization jsonSerialization,
                         IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory,
                         ITemplatingEngine templatingEngine, IMemoryTemplateConverter memoryTemplateConverter) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.templatingEngine = templatingEngine;
        this.memoryTemplateConverter = memoryTemplateConverter;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData<List<String>> latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = new HashMap<>();
        Map<String, Object> memoryForTemplate = memoryTemplateConverter.convertMemoryForTemplating(memory);
        Map<String, Object> currentMemory = (Map<String, Object>) memoryForTemplate.get("current");
        templateDataObjects.put("memory", memoryForTemplate);

        List<String> actions = latestData.getResult();

        for (String action : actions) {
            List<HttpCall> httpCalls;
            httpCalls = this.httpCalls.stream().
                    filter(httpCall -> httpCall.getActions().contains(action)).
                    collect(Collectors.toList());
            for (HttpCall call : httpCalls) {
                try {
                    Request requestConfig = call.getRequest();
                    String targetUri = targetServerUri + requestConfig.getPath();
                    String requestBody = templateValues(requestConfig.getBody(), templateDataObjects);
                    IRequest request = buildRequest(targetUri, requestBody, requestConfig, templateDataObjects);
                    IResponse response = request.send();
                    if (response.getHttpCode() != 200) {
                        String message = "HttpCall (%s) didn't return http code 200, instead %s.";
                        log.warn(String.format(message, call.getName(), response.getHttpCode()));
                        log.warn("Error Msg:" + response.getHttpCodeMessage());
                        continue;
                    }
                    if (call.isSaveResponse()) {
                        final String responseBody = response.getContentAsString();
                        String actualContentType = response.getHttpHeader().get(CONTENT_TYPE);
                        if (actualContentType != null) {
                            actualContentType = actualContentType.split(";")[0];
                        } else {
                            actualContentType = "<not-present>";
                        }

                        if (!CONTENT_TYPE_APPLICATION_JSON.startsWith(actualContentType)) {
                            String message = "HttpCall (%s) didn't return application/json as content-type, instead was (%s)";
                            log.warn(String.format(message, call.getName(), actualContentType));
                            continue;
                        }

                        Object responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                        String responseObjectName = call.getResponseObjectName();
                        Map<String, Object> templateHttpCalls = (Map<String, Object>) currentMemory.get(KEY_HTTP_CALLS);
                        if (templateHttpCalls == null) {
                            templateHttpCalls = new HashMap<>();
                            currentMemory.put(KEY_HTTP_CALLS, templateHttpCalls);
                        }

                        templateHttpCalls.put(responseObjectName, responseObject);
                        templateDataObjects.put(responseObjectName, responseObject);

                        String memoryDataName = "httpCalls:" + responseObjectName;
                        IData<Object> httpResponseData = dataFactory.createData(memoryDataName, responseObject);
                        memory.getCurrentStep().storeData(httpResponseData);

                        runPostResponse(memory, call, templateDataObjects);
                    }
                } catch (IRequest.HttpRequestException | IOException | ITemplatingEngine.TemplateEngineException e) {
                    log.error(e.getLocalizedMessage(), e);
                    throw new LifecycleException(e.getLocalizedMessage(), e);
                }
            }
        }
    }

    private void runPostResponse(IConversationMemory memory, HttpCall call, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, IOException {

        PostResponse postResponse = call.getPostResponse();
        QuickRepliesBuildingInstruction qrBuildInstruction = null;
        if (postResponse != null) {
            qrBuildInstruction = postResponse.getQrBuildInstruction();
        }

        if (qrBuildInstruction != null) {
            List<Map<String, String>> quickReplies = buildQuickReplies(qrBuildInstruction.getIterationObjectName(),
                    qrBuildInstruction.getPathToTargetArray(),
                    qrBuildInstruction.getQuickReplyValue(),
                    qrBuildInstruction.getQuickReplyExpressions(),
                    templateDataObjects);

            Context context = new Context(Context.ContextType.object, quickReplies);
            IData<Context> contextData = dataFactory.createData("context:quickReplies", context);
            memory.getCurrentStep().storeData(contextData);
        }

        if (templateDataObjects.get("eddicontext") != null) {
            Context context = new Context(Context.ContextType.string, templateDataObjects.get("eddicontext"));
            memory.getCurrentStep().storeData(dataFactory.createData("context", context)) ;
        }


    }

    private IRequest buildRequest(String targetUri, String requestBody, Request requestConfig, Map<String, Object> templateDataObjects) throws ITemplatingEngine.TemplateEngineException {
        IRequest request = httpClient.newRequest(URI.create(targetUri),
                IHttpClient.Method.valueOf(requestConfig.getMethod().toUpperCase())).
                setBodyEntity(requestBody,
                        UTF_8, requestConfig.getContentType());

        Map<String, String> headers = requestConfig.getHeaders();
        for (String headerName : headers.keySet()) {
            request.setHttpHeader(headerName, templateValues(headers.get(headerName), templateDataObjects));
        }

        Map<String, String> queryParams = requestConfig.getQueryParams();
        for (String queryParam : queryParams.keySet()) {
            request.setQueryParam(queryParam, templateValues(queryParams.get(queryParam), templateDataObjects));
        }
        return request;
    }

    private String templateValues(String toBeTemplated, Map<String, Object> properties)
            throws ITemplatingEngine.TemplateEngineException {

        return templatingEngine.processTemplate(toBeTemplated, properties);
    }

    private List<Map<String, String>> buildQuickReplies(String iterationObjectName,
                                                        String pathToTargetArray,
                                                        String quickReplyValue,
                                                        String quickReplyExpressions,
                                                        Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, IOException {

        String jsonQuickReplies = templatingEngine.processTemplate("[" +
                "[# th:each=\"" + iterationObjectName + ", iterationStatus : ${" + pathToTargetArray + "}\"]" +
                "    {" +
                "        \"value\":\"" + quickReplyValue + "\"," +
                "        \"expressions\":\"" + quickReplyExpressions + "\"" +
                "    }" +
                "    [# th:text=\"!${iterationStatus.last} ? ',':''\"/]" +
                "[/]" +
                "]", templateDataObjects);


        return jsonSerialization.deserialize(jsonQuickReplies, List.class);
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        URI uri = URI.create(uriObj.toString());

        try {
            HttpCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(uri, HttpCallsConfiguration.class);

            this.targetServerUri = httpCallsConfig.getTargetServer().toString();
            this.httpCalls = httpCallsConfig.getHttpCalls();

        } catch (ServiceException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new PackageConfigurationException(e.getMessage(), e);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Http Calls");
        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put("uri", configValue);
        return extensionDescriptor;
    }
}
