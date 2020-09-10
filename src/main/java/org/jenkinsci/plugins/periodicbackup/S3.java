/*
 * The MIT License
 *
 * Copyright (c) 2010 - 2011, Tomasz Blaszczynski, Emanuele Zattin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.periodicbackup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.acegisecurity.AccessDeniedException;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import hudson.Extension;
import hudson.RestrictedSince;
import hudson.util.FormValidation;
import hudson.util.IOUtils;
import jenkins.model.Jenkins;

/**
 *
 * LocalDirectory defines the local folder to store the backup files
 */
public class S3 extends Location {

    private String bucket;
    private String tmpDir;
    private static final Logger LOGGER = Logger.getLogger(S3.class.getName());

    @DataBoundConstructor
    public S3(String bucket, boolean enabled, String tmpDir) {
        super(enabled);
        this.bucket = bucket;
        this.setTmpDir(tmpDir);
    }

    @SuppressWarnings("deprecation")
	@Override
    public Iterable<BackupObject> getAvailableBackups() {
        AmazonS3 client = AmazonUtil.getAmazonS3Client();

        List<S3ObjectSummary> objectSummarys = client.listObjects(bucket).getObjectSummaries();
        List<String> backupObjectFileNames = new ArrayList<String>();
        List<File> backupObjectFiles = new ArrayList<File>();
        for(S3ObjectSummary objectSummary : objectSummarys) {
           if(StringUtils.endsWith(objectSummary.getKey(), BackupObject.EXTENSION)) {
                backupObjectFileNames.add(objectSummary.getKey());
                try {
                    File dir = new File(tmpDir);
                    if(!dir.isDirectory()) {
                        if(!dir.mkdir()) {
                            LOGGER.warning("Unable to make temp directory: "+tmpDir);
                            return null;
                        }
                    }
                    File file = new File(dir + objectSummary.getKey());
					IOUtils.copy(client.getObject(bucket, objectSummary.getKey()).getObjectContent(), new FileOutputStream(file));
					backupObjectFiles.add(file);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }
        }

        // The sorting will be performed according to the timestamp
        Collections.sort(backupObjectFileNames);

        return Iterables.transform(backupObjectFiles, BackupObject.getFromFile());
    }

    @Override
    public void storeBackupInLocation(Iterable<File> archives, File backupObjectFile) throws IOException {
        if (this.enabled && isBucketExists()) {
             AmazonS3 client = AmazonUtil.getAmazonS3Client();
             for (File archive : archives) {
                LOGGER.info(archive.getName() + " copying to s3 bucket " +bucket);
                client.putObject(bucket, archive.getName(), archive);
                LOGGER.info(archive.getName() + " copied to s3 bucket " +bucket);
             }
             File dir = new File(tmpDir);
             if(!dir.isDirectory()) {
                if(!dir.mkdir()) {
                    LOGGER.warning("Unable to make temp directory: "+tmpDir);
                    throw new IOException();
                }
             }
             File backupObjectFileDestination = new File(dir, backupObjectFile.getName());
             Files.copy(backupObjectFile, backupObjectFileDestination);
             client.putObject(bucket, backupObjectFile.getName(), backupObjectFileDestination);
             LOGGER.info(backupObjectFile.getName() + " copied to " + backupObjectFileDestination.getAbsolutePath());
         }
         else {
             LOGGER.warning("skipping location " + this.bucket + " since it is disabled or it does not exist.");
         }
    }

    @Override
    public Iterable<File> retrieveBackupFromLocation(final BackupObject backup, File tempDir) throws IOException, PeriodicBackupException {
        AmazonS3 client = AmazonUtil.getAmazonS3Client();

        List<String> backpFileNames = new ArrayList<String>();
        List<S3ObjectSummary> objectSummarys = client.listObjects(bucket).getObjectSummaries();
        for(S3ObjectSummary objectSummary : objectSummarys) {
           if(objectSummary.getKey().contains(Util.getFormattedDate(BackupObject.FILE_TIMESTAMP_PATTERN, backup.getTimestamp())) &&
                   !objectSummary.getKey().endsWith(BackupObject.EXTENSION)) {
               backpFileNames.add(objectSummary.getKey());
           }
        }

        Set<File> archivesInTemp = Sets.newHashSet();

        // Copy every archive to the temp dir
        for(String backupFilename : backpFileNames) {
            File copiedFile = new File(tempDir, backupFilename);
            try {
				IOUtils.copy(client.getObject(bucket, backupFilename).getObjectContent(), new FileOutputStream(copiedFile));
				archivesInTemp.add(copiedFile);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }

        return archivesInTemp;
    }

    @Override
    public void deleteBackupFiles(BackupObject backupObject) {
        LOGGER.info("Deleting backupObject...");
        String filenamePart = Util.generateFileNameBase(backupObject.getTimestamp());
        AmazonS3 client = AmazonUtil.getAmazonS3Client();

        List<S3ObjectSummary> objectSummarys = client.listObjects(bucket).getObjectSummaries();
        for(S3ObjectSummary objectSummary : objectSummarys) {
            if(StringUtils.contains(objectSummary.getKey(), filenamePart)) {
                LOGGER.info("Deleting backupObject..." +objectSummary.getKey());
                client.deleteObject(bucket, objectSummary.getKey());
                LOGGER.info("Deleted backupObject..." +objectSummary.getKey());
            }
        }
    }

    public String getDisplayName() {
        return "S3 bucket: " + bucket;
    }

    @SuppressWarnings("unused")
    public String getBucket() {
        return bucket;
    }

    @SuppressWarnings("unused")
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    @SuppressWarnings("unused")
    public String getTmpDir() {
		return tmpDir;
	}

    @SuppressWarnings("unused")
	public void setTmpDir(String tmpDir) {
		this.tmpDir = tmpDir;
	}

    private boolean isBucketExists() {
        AmazonS3 client = AmazonUtil.getAmazonS3Client();

        return client.doesBucketExistV2(bucket);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof S3) {
            S3 that = (S3) o;
            return Objects.equal(this.bucket, that.bucket)
                && Objects.equal(this.enabled, that.enabled);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(bucket, enabled);
    }


	@SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends LocationDescriptor {
        public String getDisplayName() {
            return "Amazon S3";
        }

        @RequirePOST
        @Restricted(NoExternalUse.class)
        @RestrictedSince("1.4")
        public FormValidation doTestBucket(@QueryParameter String bucket) throws AccessDeniedException {
            Jenkins.getActiveInstance().checkPermission(Jenkins.ADMINISTER);
            try {
                return FormValidation.ok(validatePath(bucket));
            } catch (FormValidation f) {
                return f;
            }
        }

        private String validatePath(String bucket) throws FormValidation {
            AmazonS3 client = AmazonUtil.getAmazonS3Client();
            if (!client.doesBucketExistV2(bucket)) {
                throw FormValidation.error(bucket + " doesn't exist or I don't have access to it!");
            }
            return "bucket \"" + bucket + "\" OK";
        }
    }
}