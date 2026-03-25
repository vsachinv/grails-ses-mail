package grails.plugins.mail.aws

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * Holds all configuration values read from the grails.mail.ses.* config block.
 * Populated by GrailsSesMailGrailsPlugin#doWithSpring and injected into SesApiMailSender.
 */
@CompileStatic
@ToString(includeNames = true, excludes = ['secretKey'])
class SesMailConfiguration {

    /**
     * AWS region where your SES identities are verified.
     * e.g. "us-east-1", "eu-west-1", "ap-southeast-1"
     */
    String region = "us-east-1"

    /**
     * IAM Access Key ID.
     *
     * Leave blank (recommended for AWS-hosted apps) to fall back to the
     * DefaultCredentialsProvider chain, which checks – in order:
     *   1. Env vars AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
     *   2. ~/.aws/credentials file
     *   3. EC2 instance profile / ECS task role / EKS IRSA
     */
    String accessKey = ""

    /**
     * IAM Secret Access Key.
     * Leave blank when using instance roles or environment-variable credentials.
     */
    String secretKey = ""

    /**
     * Default FROM address injected into outbound messages that carry no From header.
     * Must be a verified SES identity (individual address or sending domain).
     * Falls back to grails.mail.default.from when not set here.
     */
    String defaultFrom = ""

    /**
     * Optional SES Configuration Set name attached to every send request.
     * Enables open/click tracking, bounce/complaint event destinations, etc.
     * Leave blank to send without a configuration set.
     */
    String configurationSetName = ""
}
