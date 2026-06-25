package com.debateai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.debateai.service.TextSimilarityService;

class ModeratorValidationTest {

    private ModeratorAgent moderatorAgent;
    private Method enforceMethod;

    @BeforeEach
    void setUp() throws Exception {
        moderatorAgent = new ModeratorAgent(new TextSimilarityService());
        enforceMethod = ModeratorAgent.class.getDeclaredMethod("enforceSingleWinnerDecision", String.class, String.class);
        enforceMethod.setAccessible(true);
    }

    private String invokeEnforce(String topic, String recommendation) throws Exception {
        try {
            return (String) enforceMethod.invoke(moderatorAgent, topic, recommendation);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void shouldExtractWinnerForBinaryDebate() throws Exception {
        String topic = "Java or Python?";
        String recommendation = "Python is the winner because of its ease of use.";
        assertEquals("Python", invokeEnforce(topic, recommendation));
    }

    @Test
    void shouldExtractFreeFormWinnerForPropositionDebate() throws Exception {
        String topic = "Should AI replace software engineers?";
        String recommendation = "AI should augment rather than replace engineers. This is because... \n Bullet 1";
        assertEquals("AI should augment rather than replace engineers.", invokeEnforce(topic, recommendation));
        
        topic = "Is remote work better than office work?";
        recommendation = "It depends on the specific team and role.";
        assertEquals("It depends on the specific team and role.", invokeEnforce(topic, recommendation));
        
        topic = "Is nuclear power beneficial?";
        recommendation = "Yes, nuclear power provides stable base-load energy.";
        assertEquals("Yes, nuclear power provides stable base-load energy.", invokeEnforce(topic, recommendation));
    }
}
