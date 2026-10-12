package grails.plugins.mail.aws

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for the SubscribeURL trust check in SesSnsHandlerController.
 *
 * The check runs on the parsed host so that a URL whose path merely contains
 * ".amazonaws.com/" cannot trick the controller into issuing a GET to an
 * attacker-controlled host.
 */
class SesSnsHandlerControllerSpec extends Specification {

    @Unroll
    def "isTrustedSnsUrl accepts genuine SNS URL: #url"() {
        expect:
        SesSnsHandlerController.isTrustedSnsUrl(url)

        where:
        url << [
                'https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=abc',
                'https://sns.eu-west-1.amazonaws.com/confirm',
                'https://SNS.AP-SOUTHEAST-1.AMAZONAWS.COM/confirm',
                'https://sns.us-gov-west-1.amazonaws.com/confirm',
        ]
    }

    @Unroll
    def "isTrustedSnsUrl rejects untrusted URL: #url"() {
        expect:
        !SesSnsHandlerController.isTrustedSnsUrl(url)

        where:
        url << [
                null,
                '',
                'http://sns.us-east-1.amazonaws.com/confirm',                   // not HTTPS
                'https://sns.attacker.example/x.amazonaws.com/confirm',         // host mismatch, path matches
                'https://sns.us-east-1.amazonaws.com.attacker.example/confirm', // suffix spoof
                'https://attacker-sns.us-east-1.amazonaws.com/confirm',         // wrong prefix
                'https://sns.amazonaws.com/confirm',                            // no region segment
                'https://sns.us-east-1.amazonaws.com@attacker.example/confirm', // userinfo trick
                'https://evil.example/?u=https://sns.us-east-1.amazonaws.com/', // query trick
                'not a url',
        ]
    }
}
