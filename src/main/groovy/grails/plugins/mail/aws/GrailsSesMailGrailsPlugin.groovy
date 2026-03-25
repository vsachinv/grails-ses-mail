package grails.plugins.mail.aws

import groovy.util.logging.Slf4j
import grails.plugins.Plugin

/**
 * GrailsSesMailGrailsPlugin
 * =========================
 * Registers the AWS SES API-backed JavaMailSender and the SNS signature
 * verifier as Spring beans.
 *
 * Transport approach
 * ------------------
 * This plugin uses the AWS SES v2 API (SendRawEmail) exclusively.
 * SMTP transport is not handled here – grails-mail covers SMTP out of the box.
 * This plugin's only responsibility is to provide the native API transport path.
 *
 * How bean overriding works
 * -------------------------
 * grails-mail (and optionally asynchronous-mail) each register a Spring bean
 * named 'mailSender' during their own doWithSpring phase.
 *
 * By declaring loadAfter = ['mail', 'asynchronous-mail'], this plugin's
 * doWithSpring runs last. When ses.enabled = true, its 'mailSender' bean
 * definition replaces whatever the earlier plugins registered.
 *
 * Both grails-mail and asynchronous-mail resolve the 'mailSender' bean from
 * the application context at send time (not cached at startup), so the
 * replacement takes full effect without any changes to the consuming application.
 *
 * When disabled
 * -------------
 * When grails.mail.ses.enabled = false (the default), the mailSender bean is
 * left untouched. However, the SnsSignatureVerifier bean is ALWAYS registered
 * so that the /ses/sns endpoint can reject spoofed SNS requests even when the
 * application is temporarily using SMTP for sending.
 */
@Slf4j
class GrailsSesMailGrailsPlugin extends Plugin {

    def grailsVersion = "6.2.0 > *"
    def title         = "Grails SES Mail"
    def version       = "1.0.0"
    def description   = """\
        AWS SES v2 API transport for the grails-mail and asynchronous-mail plugins.
        Replaces the 'mailSender' bean with a native SES API sender when enabled.
        Works as a drop-in: no changes required in the consuming application.
    """.stripIndent().trim()
    def profiles      = ['web']

    // Must load after both mail plugins so this plugin's bean definition wins.
    // asynchronous-mail itself already loads after mail, so this order is safe.
    def loadAfter = ['mail', 'asynchronous-mail']

    // -------------------------------------------------------------------------
    // Spring bean wiring
    // -------------------------------------------------------------------------

    Closure doWithSpring() {
        { ->
            def cfg = application.config
            def ses = cfg.getProperty('grails.mail.ses', Map, [:])

            // ── Always register SnsSignatureVerifier ─────────────────────────
            // Registered unconditionally so the /ses/sns endpoint can reject
            // spoofed requests regardless of whether ses.enabled is true or false.
            def sns         = (ses instanceof Map && ses.sns instanceof Map) ? ses.sns : [:]
            boolean verifySig = resolveBoolean(sns, 'verifySignature', true)

            snsSignatureVerifier(SnsSignatureVerifier) {
                verifySignature = verifySig
            }
            log.info "grails-ses-mail: SnsSignatureVerifier registered (verifySignature={})", verifySig

            // ── Register default BounceSuppressionStore ────────────────────────
            // Consuming apps can override by declaring their own bean named
            // 'bounceSuppressionStore' in resources.groovy or doWithSpring
            // (e.g. backed by Redis, Hazelcast, DB).
            bounceSuppressionStore(InMemoryBounceSuppressionStore)
            log.info "grails-ses-mail: InMemoryBounceSuppressionStore registered as default"

            // ── Conditionally override mailSender ─────────────────────────────
            if (!resolveBoolean(ses, 'enabled', false)) {
                log.info "grails-ses-mail: ses.enabled=false – existing mailSender bean left unchanged"
                return
            }

            // defaultFrom: ses.defaultFrom takes precedence, then falls back to
            // the standard grails.mail.default.from used by grails-mail itself.
            String defaultFrom = resolveString(ses, 'defaultFrom',
                    cfg.getProperty('grails.mail.default.from', String, ''))

            SesMailConfiguration sesConfig = new SesMailConfiguration(
                    region              : resolveString(ses, 'region',               'us-east-1'),
                    accessKey           : resolveString(ses, 'accessKey',
                                              System.getenv('AWS_ACCESS_KEY_ID')     ?: ''),
                    secretKey           : resolveString(ses, 'secretKey',
                                              System.getenv('AWS_SECRET_ACCESS_KEY') ?: ''),
                    defaultFrom         : defaultFrom,
                    configurationSetName: resolveString(ses, 'configurationSetName', ''),
            )

            log.info "grails-ses-mail: registering SesApiMailSender – region={}, configurationSet='{}'",
                    sesConfig.region,
                    sesConfig.configurationSetName ?: "(none)"

            // Override the 'mailSender' bean registered by grails-mail.
            // Both grails-mail and asynchronous-mail look up this exact bean name.
            mailSender(SesApiMailSender) {
                sesConfiguration = sesConfig
            }
        }
    }

    void doWithDynamicMethods() {}
    void doWithApplicationContext() {}
    void onChange(Map<String, Object> event) {}
    void onConfigChange(Map<String, Object> event) {}
    void onShutdown(Map<String, Object> event) {}

    // -------------------------------------------------------------------------
    // Config resolution helpers
    // Handle both Map (Groovy config) and ConfigObject safely.
    // -------------------------------------------------------------------------

    private static String resolveString(def map, String key, String defaultVal) {
        def v = (map instanceof Map) ? map[key] : null
        (v != null && v.toString() != 'null' && v.toString() != '') ? v.toString() : defaultVal
    }

    private static boolean resolveBoolean(def map, String key, boolean defaultVal) {
        def v = (map instanceof Map) ? map[key] : null
        v != null ? (v as boolean) : defaultVal
    }
}
