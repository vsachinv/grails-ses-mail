package grails.plugins.mail.aws

/**
 * SesMailUrlMappings
 * ==================
 * Ships with the grails-ses-mail plugin and registers the SNS webhook endpoint
 * automatically.  Consuming applications do NOT need to add any UrlMappings
 * entry for the SNS handler.
 *
 * Default endpoint
 * ----------------
 *   POST /ses/sns
 *
 * This path is deliberately specific to avoid clashing with common application
 * routes.  Configure the path in your application if a conflict arises:
 *
 *   grails.mail.ses.sns.endpointPath = "/hooks/aws-ses"
 *
 * Note: changing the path requires updating the SNS subscription URL in the
 * AWS Console accordingly.
 *
 * Action mapping
 * --------------
 * The controller exposes a single action 'handleSnsEvent'.
 * The URL mapping binds POST /ses/sns directly to that action so no $action
 * variable is needed (and no other actions on the controller are exposed).
 *
 * Why a fixed action rather than $action?
 * ----------------------------------------
 * Using "/ses/sns/$action" would expose any future controller action at an
 * unauthenticated public URL.  Binding to a single named action is safer and
 * makes the contract between AWS and the application explicit.
 */
class SesMailUrlMappings {

    static mappings = {
        "/ses/sns"(controller: 'sesSnsHandler', action: 'handleSnsEvent', method: 'POST')
    }
}
