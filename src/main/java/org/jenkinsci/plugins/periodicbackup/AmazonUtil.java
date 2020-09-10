package org.jenkinsci.plugins.periodicbackup;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;

public class AmazonUtil  {
    public static AmazonS3 getAmazonS3Client() {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        builder.setRegion(CredentialsAwsGlobalConfiguration.get().getRegion());
        builder.setCredentials(CredentialsAwsGlobalConfiguration.get().getCredentials());
        AmazonS3 client = builder.build();
        
        return client;
    }
}
