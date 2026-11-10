package com.shinyhut.vernacular.protocol.messages

import spock.lang.Specification

class SetServerInputTest extends Specification {

    def "should encode a SetServerInput message"() {
        given:
        def message = new SetServerInput(disabled)
        def output = new ByteArrayOutputStream()

        when:
        message.encode(output)

        then:
        output.toByteArray() == expected as byte[]

        where:
        disabled | expected
        true     | [0x09, 0x01, 0x00, 0x00]
        false    | [0x09, 0x00, 0x00, 0x00]
    }
}
