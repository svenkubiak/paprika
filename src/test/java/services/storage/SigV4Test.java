package services.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class SigV4Test {

    /** The GET example from the AWS Signature Version 4 documentation. */
    @Test
    void signsThePublishedGetExample() {
        String canonical = String.join("\n",
                "GET",
                "/test.txt",
                "",
                "host:examplebucket.s3.amazonaws.com",
                "range:bytes=0-9",
                "x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "x-amz-date:20130524T000000Z",
                "",
                "host;range;x-amz-content-sha256;x-amz-date",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        assertThat(SigV4.signature(
                        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                        "us-east-1",
                        "20130524T000000Z",
                        canonical),
                is("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
    }

    @Test
    void encodesAPathWithoutTouchingTheSlashes() {
        assertThat(SigV4.encode("tenant a/file", false), is("tenant%20a/file"));
        assertThat(SigV4.encode("tenant a/file", true), is("tenant%20a%2Ffile"));
    }

    @Test
    void sortsAndEncodesTheQueryTheWayTheSignatureRequires() {
        assertThat(S3Endpoint.canonicalQuery(java.util.Map.of(
                        "prefix", "tenant-a/",
                        "list-type", "2",
                        "delimiter", "/")),
                is("delimiter=%2F&list-type=2&prefix=tenant-a%2F"));
    }
}
