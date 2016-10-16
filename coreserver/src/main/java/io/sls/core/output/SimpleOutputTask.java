package io.sls.core.output;

import com.google.inject.Key;
import com.google.inject.name.Names;
import io.sls.core.lifecycle.AbstractLifecycleTask;
import io.sls.core.lifecycle.ILifecycleTask;
import io.sls.core.lifecycle.LifecycleException;
import io.sls.core.lifecycle.PackageConfigurationException;
import io.sls.core.runtime.client.configuration.ResourceClientLibrary;
import io.sls.core.runtime.service.ServiceException;
import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.memory.impl.Data;
import io.sls.resources.rest.output.model.OutputConfiguration;
import io.sls.resources.rest.output.model.OutputConfigurationSet;
import io.sls.runtime.DependencyInjector;
import io.sls.utilities.RuntimeUtilities;

import javax.inject.Singleton;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Spoken Language System. Core.
 * User: jarisch
 * Date: 12.03.12
 * Time: 16:36
 */
@Singleton
public class SimpleOutputTask extends AbstractLifecycleTask implements ILifecycleTask {
    private SimpleOutput simpleOutput;
    private ResourceClientLibrary resourceClientLibrary;
    private final String ACTION_KEY = "action";
    private String separator = " ";

    public SimpleOutputTask() {
        this.simpleOutput = new SimpleOutput();
        DependencyInjector dependencyInjector = DependencyInjector.getInstance();
        String configurationServerURI = dependencyInjector.getInstance(Key.get(String.class, Names.named("system.configurationServerURI")));
        IRestInterfaceFactory restInterfaceFactory = dependencyInjector.getInstance(IRestInterfaceFactory.class);
        resourceClientLibrary = new ResourceClientLibrary(restInterfaceFactory,configurationServerURI);
    }

    @Override
    public String getId() {
        return "io.sls.output.SimpleOutput";
    }

    @Override
    public Object getComponent() {
        return simpleOutput;
    }

    @Override
    public List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        List<IOutputFilter> outputFilters = new LinkedList<IOutputFilter>();
        IData latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }
        List<String> actions = (List<String>) latestData.getResult();
        for (String action : actions) {
            int occurrence = countActionOccurrence(memory.getPreviousSteps(), action);
            outputFilters.add(new OutputFilter(action, occurrence));
        }

        List<List<OutputEntry>> possibleOutputs = simpleOutput.getOutputs(outputFilters);

        if (possibleOutputs.isEmpty()) {
            return;
        }

        for (List<OutputEntry> possibleOutput : possibleOutputs) {
            memory.getCurrentStep().storeData(new Data("output:action:" + possibleOutput.get(0).getKey(), null, simpleOutput.convert(possibleOutput)));
        }

        List<IData> allOutputParts = memory.getCurrentStep().getAllData("output:action");
        StringBuilder finalOutput = new StringBuilder();
        for (IData outputPart : allOutputParts) {
            finalOutput.append(outputPart.getResult()).append(separator);
        }

        if (!allOutputParts.isEmpty()) {
            finalOutput.delete(finalOutput.length() - separator.length(), finalOutput.length());
        }

        Data finalOutputData = new Data("output:final", finalOutput.toString());
        finalOutputData.setPublic(true);
        memory.getCurrentStep().storeData(finalOutputData);
    }

    private int countActionOccurrence(IConversationMemory.IConversationStepStack conversationStepStack,
                                      String action) {
        int count = 0;
        for (int i = 0; i < conversationStepStack.size(); i++) {
            IConversationMemory.IConversationStep conversationStep = conversationStepStack.get(i);
            IData actionsData = conversationStep.getLatestData(ACTION_KEY);
            if (actionsData != null) {
                List<String> actions = (List<String>) actionsData.getResult();
                if (actions.contains(action)) {
                    count++;
                }
            }
        }

        return count;
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        URI uri = URI.create(uriObj.toString());

        try {
            OutputConfigurationSet outputConfigurationSet = resourceClientLibrary.getResource(uri, OutputConfigurationSet.class);

            for (OutputConfiguration outputConfiguration : outputConfigurationSet.getOutputs()) {
                String key = outputConfiguration.getKey();
                int occurrence = outputConfiguration.getOccurrence();
                for (String text : outputConfiguration.getOutputValues()) {
                    if (!RuntimeUtilities.isNullOrEmpty(text)) {
                        simpleOutput.addOutputEntry(new OutputEntry(key, text, occurrence));
                    }
                }
            }
        } catch (ServiceException e) {
            String message = "Error while fetching OutputConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }
}