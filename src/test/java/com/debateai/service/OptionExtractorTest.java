package com.debateai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OptionExtractorTest {

    @Test
    void shouldExtractTwoOptionsWhenExactlyOneOrIsPresent() {
        assertEquals(List.of("Java", "Python"), OptionExtractor.extractOptions("Java or Python?"));
        assertEquals(List.of("Cats", "dogs"), OptionExtractor.extractOptions("Cats or dogs?"));
        assertEquals(List.of("Serverless", "Containers"), OptionExtractor.extractOptions("Prefer Serverless or Containers?"));
    }

    @Test
    void shouldExtractSingleOptionWhenNoOrIsPresent() {
        assertEquals(List.of("Should AI replace software engineers"), 
                OptionExtractor.extractOptions("Should AI replace software engineers?"));
        assertEquals(List.of("Should governments regulate AI"), 
                OptionExtractor.extractOptions("Should governments regulate AI?"));
        assertEquals(List.of("Is remote work better than office work"), 
                OptionExtractor.extractOptions("Is remote work better than office work?"));
        assertEquals(List.of("Is nuclear power beneficial"), 
                OptionExtractor.extractOptions("Is nuclear power beneficial?"));
        assertEquals(List.of("What are the pros and cons of universal basic income"), 
                OptionExtractor.extractOptions("What are the pros and cons of universal basic income?"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " \n "})
    void shouldRejectBlankTopics(String blankTopic) {
        assertThrows(IllegalArgumentException.class, () -> OptionExtractor.extractOptions(blankTopic));
    }
}
