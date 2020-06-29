package com.dferguson;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Environment;

public class StreamingIngestLabApp {
    public static String DEFAULT_REGION = "us-east-1";
    public static void main(final String[] args) {
        App app = new App();

        new StreamingIngestLabStack(app, "StreamingIngestLabStack", getEnv(args));

        app.synth();
    }

    public static Environment getEnv(final String[] args) {
        return new Environment.Builder()
                .region(getRegion(args))
                .build();
    }

    private static String getRegion(final String[] args){
        String region = DEFAULT_REGION;
        for (int i = 0; i< args.length; i++) {
            if (args[i].equalsIgnoreCase("--region")){
                region = args[i++];
            }
        }
        return region;
    }
}
