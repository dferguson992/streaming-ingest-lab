package com.dferguson;

import com.amazonaws.services.kinesisfirehose.model.ProcessorParameter;
import com.amazonaws.services.kinesisfirehose.model.ProcessorParameterName;
import com.amazonaws.services.kinesisfirehose.model.ProcessorType;
import software.amazon.awscdk.core.*;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.events.IRuleTarget;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.RuleProps;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.kinesis.IStream;
import software.amazon.awscdk.services.kinesis.Stream;
import software.amazon.awscdk.services.kinesis.StreamEncryption;
import software.amazon.awscdk.services.kinesis.StreamProps;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.kms.*;
import software.amazon.awscdk.services.kinesisfirehose.CfnDeliveryStream;
import software.amazon.awscdk.services.kinesisfirehose.CfnDeliveryStreamProps;
import com.amazonaws.services.kinesisfirehose.model.Processor;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.IBucket;

import java.io.File;
import java.util.*;

public class StreamingIngestLabStack extends Stack {
    public static Map<String, String> tags = new HashMap<>();
    static{
        tags.put("ippon:owner", "Dan Ferguson");
        tags.put("ippon:application", "streaming-ingest-lab");
        tags.put("ippon:environment", "Dev");
    }

    public StreamingIngestLabStack(final Construct scope, final String id, final Environment env) {
        this(scope, id, new StackProps.Builder()
                .env(env)
                .stackName("streaming-ingest-lab")
                .description("Streaming Ingest Lab Code")
                .tags(tags)
                .build());
    }

    public StreamingIngestLabStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // PROBLEM STATEMENT:
        // So you work for a company who has thousands of users
        // interacting with the company's application.
        // You have been tasked with capturing real-time data
        // about the users for a marketing campaign,
        //        and what you need to capture is information
        // like the person's name, their age, their gender,
        // and the location of the users who are all 21 and older.

        //////////////////////////////////////////////
        // BOILERPLATE INFRASTRUCTURE & PERMISSIONS //
        //////////////////////////////////////////////
        // Admin Role
        IRole adminRole = Role.fromRoleArn(this, id + "-GetApplicationRole", "arn:aws:iam::326646175787:role/role-admin-sre-ops-federated");

        // Managed Policies Lists
        List<IManagedPolicy> userSignUpLambdaPolicies = new ArrayList<>();
        userSignUpLambdaPolicies.add(ManagedPolicy.fromManagedPolicyArn(this, id + "-KinesisFullAccessMP", "arn:aws:iam::aws:policy/AmazonKinesisFullAccess"));
        userSignUpLambdaPolicies.add(ManagedPolicy.fromManagedPolicyArn(this, id + "-LambdaBasicExecMP", "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"));
        userSignUpLambdaPolicies.add(ManagedPolicy.fromManagedPolicyArn(this, id + "-S3FullAccessMP", "arn:aws:iam::aws:policy/AmazonS3FullAccess"));
        userSignUpLambdaPolicies.add(ManagedPolicy.fromManagedPolicyArn(this, id + "-LambdaInvokeMP", "arn:aws:iam::aws:policy/service-role/AWSLambdaRole"));

        // User Sign-Up Lambda Role
        IRole streamingIngestLabRole = Role.Builder.create(this, id +"-GetUserSignUpRole")
                .roleName("StreamingIngestLabRole")
                .description("Role assumed by User Sign Up Lambda for writing to Kinesis")
                .assumedBy(new CompositePrincipal(
                        new ServicePrincipal("lambda.amazonaws.com"),
                        new ServicePrincipal("firehose.amazonaws.com")
                ))
                .managedPolicies(userSignUpLambdaPolicies)
                .build();

        // Requirements Layer for Lambda Functions
        List<Runtime> compatibleRuntimes = new ArrayList<>();
        compatibleRuntimes.add(Runtime.PYTHON_3_7);
        LayerVersionProps requirementsLayerProps = new LayerVersionProps.Builder()
                .code(Code.fromAsset("src/main/lambdas/python_libs.zip"))
                .compatibleRuntimes(compatibleRuntimes)
                .description("General requirements layer for lambda functions using the Python runtime.")
                .build();

        // Encryption Key for Kinesis Traffic
        Key encryptionKey = new Key(this, id +  "-UserEncyrptionKey", KeyProps.builder()
                .enableKeyRotation(true)
                .alias("alias/streaming-ingest-lab-users")
                .description("Key used to encrypt user data.")
                .build());
        encryptionKey.grantEncryptDecrypt(streamingIngestLabRole);

        // Stack variables
        final IBucket destinationBucket = Bucket.fromBucketArn(this, id + "-S3Destination", "arn:aws:s3:::ippon-df-datalake");
        final String kinesisStreamName = "UserDeliveryStream";
        final String firehoseStreamName = "UserDataStreamToS3Loader";
        final String awsRegion = Objects.requireNonNull(props.getEnv()).getRegion() != null ? Objects.requireNonNull(props.getEnv()).getRegion() : "us-east-1";


        /////////////////////////
        // PROVISION USER DATA //
        /////////////////////////
        // Lambda Function that generates user data via https://randomuser.me/
        assert awsRegion != null;
        Function getUserFunction = new Function(this, id + "-GetUserFunction", FunctionProps.builder()
                .functionName("UserSignedUpFunction")
                .description("Calls https://randomuser.me/api/ on CloudWatch Event call and puts record to Kinesis Stream.")
                .code(Code.fromAsset(new File("src/main/lambdas/userSignUpFunction", "userSignUpFunction").toString()))
                .environment(Map.of(
                        "REGION", awsRegion,
                        "KINESIS_API_VERSION", "2013-12-02",
                        "KINESIS_STREAM_NAME", kinesisStreamName
                ))
                .timeout(Duration.seconds(3))
                .runtime(Runtime.PYTHON_3_7)
                .handler("app.lambda_handler")
                .memorySize(128)
                .role(streamingIngestLabRole)
                .build());
        getUserFunction.addLayers(new LayerVersion(this, id + "-GetUserFunctionRequirementsLayer", requirementsLayerProps));
        getUserFunction.grantInvoke(adminRole);

        //  Cloud Watch Event that triggers the lambda function to generate a user once per second
        List<IRuleTarget> ruleTargets = new ArrayList<>();
        ruleTargets.add(new LambdaFunction(getUserFunction));
        RuleProps getUserEventsRuleProperties = RuleProps.builder()
                .schedule(Schedule.rate(Duration.minutes(1)))
                .enabled(true)
                .targets(ruleTargets)
                .ruleName("UserSignedUpFunctionTrigger")
                .description("Periodically calls UserSignedUpFunction on a schedule.")
                .build();
        new Rule(this, id + "-GetUserFunctionEventRule", getUserEventsRuleProperties);

        //////////////////////
        // STREAM USER DATA //
        //////////////////////
        // Kinesis Stream
        IStream userDataDeliveryStream = new Stream(this, id + "-UserDataDeliveryStream", StreamProps.builder()
                .encryptionKey(encryptionKey)
                .retentionPeriod(Duration.hours(24))
                .shardCount(1)
                .streamName(kinesisStreamName)
                .encryption(StreamEncryption.KMS)
                .build());

        // Configure the Lambda Transformer
        Function transformationFunction = new Function(this, id + "-FilterUserFunction", FunctionProps.builder()
                .functionName("UserFilterFunction")
                .description("Transforms streaming input from Kineses Stream via Kinesis Firehose before dropping each record to its final destination.")
                .code(Code.fromAsset(new File("src/main/lambdas/userFilterFunction", "userFilterFunction").toString()))
                .environment(Map.of(
                        "REGION", awsRegion
                ))
                .timeout(Duration.minutes(1))
                .runtime(Runtime.PYTHON_3_7)
                .handler("app.lambda_handler")
                .memorySize(128)
                .role(streamingIngestLabRole)
                .build());
        getUserFunction.grantInvoke(adminRole);

        List<ProcessorParameter> processorParameters = new ArrayList<>();
        processorParameters.add(new ProcessorParameter()
                .withParameterName(ProcessorParameterName.LambdaArn)
                .withParameterValue(transformationFunction.getFunctionArn())
        );

        Processor lambdaProcessor = new Processor();
        lambdaProcessor.setType(ProcessorType.Lambda);
        lambdaProcessor.setParameters(processorParameters);

        List<Object> processors = new ArrayList<>();
        processors.add(lambdaProcessor);

        // Kinesis Firehose to S3
        new CfnDeliveryStream(this, id + "-KinesisToS3Stream", CfnDeliveryStreamProps.builder()
                .deliveryStreamName(firehoseStreamName)
                .deliveryStreamType("KinesisStreamAsSource")
                .kinesisStreamSourceConfiguration(CfnDeliveryStream.KinesisStreamSourceConfigurationProperty.builder()
                        .kinesisStreamArn(userDataDeliveryStream.getStreamArn())
                        .roleArn(streamingIngestLabRole.getRoleArn())
                        .build())
                .extendedS3DestinationConfiguration(CfnDeliveryStream.ExtendedS3DestinationConfigurationProperty.builder()
                        .bucketArn(destinationBucket.getBucketArn())
                        .prefix("streaming-ingest-lab/")
                        .bufferingHints(CfnDeliveryStream.BufferingHintsProperty.builder()
                                .intervalInSeconds(100)
                                .sizeInMBs(1)
                                .build())
                        .compressionFormat("UNCOMPRESSED")
                        .errorOutputPrefix("streaming-ingest-lab-errors/")
                        .roleArn(streamingIngestLabRole.getRoleArn())
                        .processingConfiguration(CfnDeliveryStream.ProcessingConfigurationProperty.builder()
                                .enabled(Boolean.TRUE)
                                .processors(processors)
                                .build())
                        .build())
                .build());

//        AWS Lambda that triggers a Glue Crawler on S3 object creation
//        Glue Crawler that creates a database of matching users
    }
}
