package com.myorg;

import software.amazon.awscdk.core.App;

import java.util.Arrays;

public class StreamingIngestLabApp {
    public static void main(final String[] args) {
        App app = new App();

        new StreamingIngestLabStack(app, "StreamingIngestLabStack");

        app.synth();
    }
}
