package com.shinyhut.vernacular.protocol.handshaking

import com.shinyhut.vernacular.client.VncSession
import com.shinyhut.vernacular.client.VernacularConfig
import com.shinyhut.vernacular.client.exceptions.NoSupportedSecurityTypesException
import com.shinyhut.vernacular.protocol.auth.MsLogon2AuthenticationHandler
import com.shinyhut.vernacular.protocol.auth.NoSecurityHandler
import com.shinyhut.vernacular.protocol.auth.VncAuthenticationHandler
import com.shinyhut.vernacular.protocol.messages.ProtocolVersion
import spock.lang.Specification
import spock.lang.Subject

import static com.shinyhut.vernacular.protocol.messages.SecurityType.*

class SecurityTypeNegotiatorTest extends Specification {

    @Subject
    def negotiator = new SecurityTypeNegotiator()

    def "should detect UltraVNC from the whole RFB 3.#minor offer #types while selecting VNC auth"() {
        given:
        def session = new VncSession(new VernacularConfig(),
                new ByteArrayInputStream(([types.size()] + types) as byte[]), new ByteArrayOutputStream())
        session.protocolVersion = new ProtocolVersion(3, minor)

        when:
        def handler = negotiator.negotiate(session)

        then:
        session.serverInputSupported
        handler instanceof VncAuthenticationHandler

        where:
        minor | types
        7     | [17, 2]
        7     | [2, 17]
        8     | [17, 2]
        8     | [2, 17]
    }

    def "should leave server input unsupported without an UltraVNC offer under RFB 3.#minor"() {
        given:
        def bytes = minor == 3 ? [0, 0, 0, 2] : [1, 2]
        def session = new VncSession(new VernacularConfig(),
                new ByteArrayInputStream(bytes as byte[]), new ByteArrayOutputStream())
        session.protocolVersion = new ProtocolVersion(3, minor)

        expect:
        !session.serverInputSupported
        negotiator.negotiate(session) instanceof VncAuthenticationHandler
        !session.serverInputSupported

        where:
        minor << [3, 7, 8]
    }

    def "should record detection before resolution but never select type 17 as authentication"() {
        given:
        def session = new VncSession(new VernacularConfig(),
                new ByteArrayInputStream([1, 17] as byte[]), new ByteArrayOutputStream())
        session.protocolVersion = new ProtocolVersion(3, 8)

        when:
        negotiator.negotiate(session)

        then:
        thrown(NoSupportedSecurityTypesException)
        session.serverInputSupported
    }

    def "for protocol version >= 3.7, should prefer 'No Authentication' if offered, or VNC authentication otherwise"() {
        given:
        def message = [serverTypes.size()]
        serverTypes.each { message += it.code }
        def bytes = new ByteArrayInputStream(message as byte[])
        def session = Mock(VncSession)
        _ * session.protocolVersion >> new ProtocolVersion(3, 7)

        when:
        def handler = negotiator.negotiate(session)

        then:
        1 * session.inputStream >> bytes
        handler.class == selected

        where:
        serverTypes             | selected
        [NONE]                  | NoSecurityHandler
        [NONE, VNC]             | NoSecurityHandler
        [NONE, VNC, MS_LOGON_2] | NoSecurityHandler
        [VNC]                   | VncAuthenticationHandler
        [VNC, MS_LOGON_2]       | VncAuthenticationHandler
        [MS_LOGON_2]            | MsLogon2AuthenticationHandler
    }

    def "for protocol version 3.3, must accept whatever security type is selected by the server"() {
        given:
        def message = [0, 0, 0, serverType.code]
        def bytes = new ByteArrayInputStream(message as byte[])
        def session = Mock(VncSession)
        _ * session.protocolVersion >> new ProtocolVersion(3, 3)

        when:
        def handler = negotiator.negotiate(session)

        then:
        1 * session.inputStream >> bytes
        handler.class == selected

        where:
        serverType | selected
        NONE       | NoSecurityHandler
        VNC        | VncAuthenticationHandler
        MS_LOGON_2 | MsLogon2AuthenticationHandler
    }

}
