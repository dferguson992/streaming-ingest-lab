package com.dferguson;

import software.amazon.awscdk.core.App;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.Test;
import software.amazon.awscdk.core.Environment;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class StreamingIngestLabTest {
    private final static ObjectMapper JSON =
        new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);

    @Test
    public void testStack() throws IOException {
        App app = new App();
        StreamingIngestLabStack stack = new StreamingIngestLabStack(app, "test", new Environment.Builder()
                .region("us-east-1")
                .build());

//        JsonNode actual = JSON.valueToTree(app.synth().getStackArtifact(stack.getArtifactId()).getTemplate());
//        assertEquals(new ObjectMapper().createObjectNode(), actual);
    }
}
