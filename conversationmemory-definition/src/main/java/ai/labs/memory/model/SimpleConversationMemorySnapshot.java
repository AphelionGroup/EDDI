package ai.labs.memory.model;

import ai.labs.models.ConversationState;
import ai.labs.models.Deployment;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class SimpleConversationMemorySnapshot {
    private String botId;
    private Integer botVersion;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private int redoCacheSize;
    private List<SimpleConversationStep> conversationSteps = new LinkedList<>();

    @Getter
    @Setter
    public static class SimpleConversationStep {
        private List<ConversationStepData> conversationStep = new LinkedList<>();
        private Date timestamp;
    }

    @AllArgsConstructor
    @Getter
    @Setter
    public static class ConversationStepData {
        private String key;
        private Object value;
    }
}
