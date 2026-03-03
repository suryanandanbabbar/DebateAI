package com.debateai.agent;

import com.debateai.dto.AgentResponse;
import com.debateai.dto.DebateResult;
import java.util.List;

public interface DebateAgent {

    String agentName();

    String viewpoint();

    AgentResponse generate(String topic);

    default boolean isModerator() {
        return false;
    }

    default DebateResult moderate(String topic, List<AgentResponse> responses) {
        throw new UnsupportedOperationException(agentName() + " does not support moderation");
    }
}
