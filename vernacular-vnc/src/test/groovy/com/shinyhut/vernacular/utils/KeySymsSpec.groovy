package com.shinyhut.vernacular.utils

import spock.lang.Specification
import spock.lang.Unroll

class KeySymsSpec extends Specification {

    @Unroll
    def "forKeyCode(#keyCode) should return X11 keysym #expectedKeySym"() {
        expect:
        KeySyms.forKeyCode(keyCode) == Optional.of(expectedKeySym)

        where:
        keyCode | expectedKeySym
        0x08    | 0xff08         // Backspace
        0x09    | 0xff09         // Tab
        0x0A    | 0xff0d         // Enter
        0x1B    | 0xff1b         // Escape
        0x9B    | 0xff63         // Insert
        0x7F    | 0xffff         // Delete
        0x24    | 0xff50         // Home
        0x23    | 0xff57         // End
        0x21    | 0xff55         // Page Up
        0x22    | 0xff56         // Page Down
        0x25    | 0xff51         // Left
        0x26    | 0xff52         // Up
        0x27    | 0xff53         // Right
        0x28    | 0xff54         // Down
        0x70    | 0xffbe         // F1
        0x71    | 0xffbf         // F2
        0x72    | 0xffc0         // F3
        0x73    | 0xffc1         // F4
        0x74    | 0xffc2         // F5
        0x75    | 0xffc3         // F6
        0x76    | 0xffc4         // F7
        0x77    | 0xffc5         // F8
        0x78    | 0xffc6         // F9
        0x79    | 0xffc7         // F10
        0x7A    | 0xffc8         // F11
        0x7B    | 0xffc9         // F12
        0x10    | 0xffe1         // Shift
        0x11    | 0xffe3         // Control
        0x9D    | 0xffe7         // Meta
        0x12    | 0xffe9         // Alt
    }

    def "forKeyCode should return empty for unknown key codes"() {
        expect:
        KeySyms.forKeyCode(0xFF) == Optional.empty()
        KeySyms.forKeyCode(0x00) == Optional.empty()
        KeySyms.forKeyCode(9999) == Optional.empty()
    }

    def "map should return keysym for known key codes regardless of symbol"() {
        expect:
        KeySyms.map(0x0A, (char) 'x', false) == Optional.of(0xff0d)
        KeySyms.map(0x08, (char) 0xFFFF, false) == Optional.of(0xff08)
    }

    def "map should return control character mapping when not shift-down"() {
        expect:
        KeySyms.map(0, (char) 0x01, false) == Optional.of(0x0061)
        KeySyms.map(0, (char) 0x00, false) == Optional.of(0x0040)
        KeySyms.map(0, (char) 0x1a, false) == Optional.of(0x007a)
    }

    def "map should return shift control character mapping when shift is down"() {
        expect:
        KeySyms.map(0, (char) 0x01, true) == Optional.of(0x0041)
        KeySyms.map(0, (char) 0x1a, true) == Optional.of(0x005a)
    }

    def "map should return symbol as keysym for regular characters"() {
        expect:
        KeySyms.map(0, (char) 'a', false) == Optional.of((int) 'a')
        KeySyms.map(0, (char) 'Z', false) == Optional.of((int) 'Z')
        KeySyms.map(0, (char) '5', false) == Optional.of((int) '5')
    }

    def "map should return empty for CHAR_UNDEFINED with unknown keycode"() {
        expect:
        KeySyms.map(0, (char) 0xFFFF, false) == Optional.empty()
    }
}
