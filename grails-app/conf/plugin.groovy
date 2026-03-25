// =============================================================================
// grails-ses-mail – default plugin configuration
// =============================================================================
// This file ships with the plugin and declares the defaults for every
// supported config key under grails.mail.ses.*.
//
// Consuming applications override any of these in their own plugin.groovy
// or application.yml.  Keys not set by the application retain the defaults
// defined here.
// =============================================================================

grails {
    mail {
        ses {
            // ------------------------------------------------------------------
            // enabled (Boolean) – default: false
            //
            // Master switch.  When false the plugin is completely dormant: the
            // 'mailSender' bean from grails-mail is left untouched and the
            // application behaves as if this plugin were not present.
            // Set to true to route all outbound mail through the SES API.
            // ------------------------------------------------------------------
            enabled = false

            // ------------------------------------------------------------------
            // region (String) – default: "us-east-1"
            //
            // The AWS region where your SES sending identities are verified.
            // Common values:
            //   "us-east-1"        US East (N. Virginia)
            //   "eu-west-1"        EU (Ireland)
            //   "ap-southeast-1"   Asia Pacific (Singapore)
            // ------------------------------------------------------------------
            region = System.getenv("AWS_REGION") ?: "us-east-1"

            // ------------------------------------------------------------------
            // accessKey / secretKey (String) – default: "" (use env / role)
            //
            // Explicit IAM credentials.  Leave blank (recommended) to fall back
            // to the DefaultCredentialsProvider chain, which resolves credentials
            // in this order:
            //   1. Env vars  AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
            //   2. ~/.aws/credentials
            //   3. EC2 instance profile / ECS task role / EKS IRSA
            // ------------------------------------------------------------------
            accessKey = System.getenv("AWS_ACCESS_KEY_ID")     ?: ""
            secretKey = System.getenv("AWS_SECRET_ACCESS_KEY") ?: ""

            // ------------------------------------------------------------------
            // defaultFrom (String) – default: "" (falls back to grails.mail.default.from)
            //
            // The FROM address used when a message carries no From header.
            // Must be a verified SES identity (individual address or domain).
            // When blank, the plugin falls back to grails.mail.default.from.
            // ------------------------------------------------------------------
            defaultFrom = System.getenv("AWS_SES_DEFAULT_FROM") ?: ""

            // ------------------------------------------------------------------
            // configurationSetName (String) – default: "" (no configuration set)
            //
            // Optional SES Configuration Set attached to every send request.
            // Enables open/click tracking, bounce/complaint SNS event routing,
            // reputation dashboards, etc.
            // Leave blank to send without attaching a configuration set.
            // ------------------------------------------------------------------
            configurationSetName = ""

            // ------------------------------------------------------------------
            // sns.verifySignature (Boolean) – default: true
            //
            // Controls whether inbound SNS HTTP POST payloads are verified
            // against the AWS RSA signature before processing.
            //
            // NEVER set false in production.  Valid reasons to set false:
            //   - Local development with a simulated SNS payload (e.g. curl)
            //   - Integration tests that POST mock payloads directly
            // ------------------------------------------------------------------
            sns {
                verifySignature = true
            }
        }
    }
}

// =============================================================================
// SNS webhook endpoint
// =============================================================================
// The plugin registers the following URL automatically via SesMailUrlMappings:
//
//   POST /ses/sns
//
// Subscribe this URL in the AWS SNS Console as the HTTP/HTTPS endpoint for
// the topic receiving SES Bounce, Complaint, and Delivery events.
//
// AWS will POST a SubscriptionConfirmation first; SesSnsHandlerController
// confirms it automatically by visiting the provided SubscribeURL.
//
// No manual UrlMappings entry is required in the consuming application.
// =============================================================================
