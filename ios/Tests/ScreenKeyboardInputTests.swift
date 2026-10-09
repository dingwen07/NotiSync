import Foundation

@main
struct ScreenKeyboardInputTests {
    static func main() {
        var assertions = 0
        func expect(_ condition: Bool, _ message: String = "", line: UInt = #line) {
            precondition(condition, "Line \(line): \(message)")
            assertions += 1
        }
        func event(_ usage: Int, _ text: String = "", _ flags: IOSScreenKeyModifiers = [],
                   _ phase: IOSScreenKeyEvent.Phase = .down) -> IOSScreenKeyEvent {
            IOSScreenKeyEvent(phase: phase, usage: usage, characters: text, modifiers: flags)
        }
        func key(_ code: Int32, _ meta: UInt32 = 0, up: Bool = false,
                 repeatCount: UInt32 = 0) -> IOSScreenKeyboardCommand {
            .key(action: up ? 1 : 0, code: code, repeatCount: repeatCount, metaState: meta)
        }

        // Letters, both cases, all digits and the complete US punctuation set use text.
        let printable: [(Int, String, String)] = (0..<26).map {
            (0x04 + $0, String(UnicodeScalar(97 + $0)!), String(UnicodeScalar(65 + $0)!))
        } + [
            (0x1e, "1", "!"), (0x1f, "2", "@"), (0x20, "3", "#"), (0x21, "4", "$"),
            (0x22, "5", "%"), (0x23, "6", "^"), (0x24, "7", "&"), (0x25, "8", "*"),
            (0x26, "9", "("), (0x27, "0", ")"), (0x2d, "-", "_"), (0x2e, "=", "+"),
            (0x2f, "[", "{"), (0x30, "]", "}"), (0x31, "\\", "|"), (0x33, ";", ":"),
            (0x34, "'", "\""), (0x35, "`", "~"), (0x36, ",", "<"), (0x37, ".", ">"),
            (0x38, "/", "?"), (0x2c, " ", " ")
        ]
        for (usage, plain, shifted) in printable {
            for (text, flags) in [(plain, IOSScreenKeyModifiers()), (shifted, .shift)] {
                var input = IOSScreenKeyboardInput()
                expect(input.handle(event(usage, text, flags)) == [.text(text)])
                expect(input.handle(event(usage, text, flags, .up)) == [])
                expect(input.releaseAll().isEmpty)
            }
            for (flags, meta) in [(IOSScreenKeyModifiers.control, UInt32(0x1000)),
                                  (.alternate, 0x2), (.command, 0x10000),
                                  ([.control, .shift], 0x1001), ([.command, .alternate], 0x10002)] {
                var input = IOSScreenKeyboardInput()
                let remoteCode = IOSScreenKeyboardInput.androidKeyCode(for: usage)!
                expect(input.handle(event(usage, shifted, flags)) == [key(remoteCode, meta)])
                expect(input.handle(event(usage, "", flags, .up)) == [key(remoteCode, meta, up: true)])
            }
        }

        // All left/right modifiers retain their own Android identity and the matching state bit.
        let modifiers: [(Int, Int32, IOSScreenKeyModifiers, UInt32)] = [
            (0xe0, 113, .control, 0x3000), (0xe4, 114, .control, 0x5000),
            (0xe1, 59, .shift, 0x41), (0xe5, 60, .shift, 0x81),
            (0xe2, 57, .alternate, 0x12), (0xe6, 58, .alternate, 0x22),
            (0xe3, 117, .command, 0x30000), (0xe7, 118, .command, 0x50000)
        ]
        for (usage, code, flags, meta) in modifiers {
            var input = IOSScreenKeyboardInput()
            expect(input.handle(event(usage, "", flags)) == [key(code, meta)])
            expect(input.handle(event(0x50, "", flags)) == [key(21, meta)])
            expect(input.releaseAll() == [key(21, up: true), key(code, up: true)])
            expect(input.handle(event(0x50, "", [], .up)) == nil)
            expect(input.releaseAll().isEmpty)
        }

        var input = IOSScreenKeyboardInput()
        expect(input.handle(event(0xe0, "", .control)) == [key(113, 0x3000)])
        expect(input.handle(event(0xe4, "", .control)) == [key(114, 0x7000)])
        expect(input.handle(event(0xe0, "", .control, .up)) == [key(113, 0x5000, up: true)])
        expect(input.handle(event(0x2c, " ", .control)) == [key(62, 0x5000)])
        expect(input.handle(event(0xe4, "", [], .up)) == [key(114, up: true)])
        // Repeat/release stays raw even after Ctrl is released; no stray inserted space.
        expect(input.handle(event(0x2c, " ")) == [key(62, repeatCount: 1)])
        expect(input.handle(event(0x2c, " ", [], .up)) == [key(62, up: true)])
        expect(input.handle(event(0x04, "a")) == [.text("a")])
        expect(input.handle(event(0x04, "a", .control)) == [])
        expect(input.handle(event(0x04, "A", .shift)) == [.text("A")])
        expect(input.handle(event(0x04, "", [], .up)) == [])

        let rawKeys: [(Int, Int32)] = [
            (0x28, 66), (0x29, 111), (0x2a, 67), (0x2b, 61), (0x39, 115),
            (0x46, 120), (0x47, 116), (0x48, 121), (0x49, 124), (0x4a, 122),
            (0x4b, 92), (0x4c, 112), (0x4d, 123), (0x4e, 93), (0x4f, 22),
            (0x50, 21), (0x51, 20), (0x52, 19), (0x53, 143), (0x58, 160)
        ] + (0..<12).map { (0x3a + $0, Int32(131 + $0)) }
        for (usage, code) in rawKeys {
            expect(input.handle(event(usage, "", .shift)) == [key(code, 1)])
            expect(input.handle(event(usage, "", [], .cancelled)) == [key(code, up: true)])
        }
        let numpad: [(Int, Int32)] = [
            (0x54, 154), (0x55, 155), (0x56, 156), (0x57, 157), (0x62, 144),
            (0x63, 158), (0x67, 161), (0x85, 159), (0x86, 161)
        ] + (0..<9).map { (0x59 + $0, Int32(145 + $0)) }
        for (usage, code) in numpad {
            expect(input.handle(event(usage, "1", .control)) == [key(code, 0x1000)])
            expect(input.releaseAll() == [key(code, up: true)])
        }
        // Numeric-pad membership must not fabricate a Num Lock modifier.
        expect(input.handle(event(0x58, "\r", IOSScreenKeyModifiers(rawValue: 1 << 21))) == [key(160)])
        expect(input.releaseAll() == [key(160, up: true)])
        expect(input.handle(event(0x04, "A", .capsLock)) == [.text("A")])
        expect(input.handle(event(0x04, "", [], .up)) == [])
        expect(input.handle(event(0x4f, "", [.capsLock, .control])) == [key(22, 0x101000)])
        expect(input.releaseAll() == [key(22, up: true)])

        // Unsupported usages/shortcuts and UIKit function-key sentinels stay local.
        for usage in [0, 1, 0x66, 0x68, 0x73, 0x7f, 0xffff] {
            expect(IOSScreenKeyboardInput.androidKeyCode(for: usage) == nil)
            expect(input.handle(event(usage, "", .control)) == nil)
            expect(input.handle(event(usage, "\u{f704}")) == nil)
            expect(input.handle(event(usage, "", [], .up)) == nil)
        }
        expect(input.handle(event(0x68, "x", .control)) == nil)
        // Layout-produced text uses the existing UTF-8 path, without promising full Unicode injection.
        expect(input.handle(event(0x1c, "z")) == [.text("z")])
        expect(input.handle(event(0x1c, "", [], .up)) == [])
        expect(input.handle(event(0x08, "é")) == [.text("é")])
        expect(input.releaseAll().isEmpty)

        expect(key(62, 0x1001, repeatCount: 3).frame ==
            Data([0, 0, 0, 0, 0, 62, 0, 0, 0, 3, 0, 0, 0x10, 1]))
        expect(key(118, up: true).frame == Data([0, 1, 0, 0, 0, 118, 0, 0, 0, 0, 0, 0, 0, 0]))
        expect(IOSScreenKeyboardCommand.text("!").frame == Data([1, 0, 0, 0, 1, 33]))
        expect(IOSScreenKeyboardCommand.text("é").frame == Data([1, 0, 0, 0, 2, 0xc3, 0xa9]))
        expect(IOSScreenKeyboardCommand.text("").frame.isEmpty)
        expect(IOSScreenKeyboardCommand.text(String(repeating: "é", count: 150)).frame.count == 305)
        expect(IOSScreenKeyboardCommand.text(String(repeating: "é", count: 151)).frame.isEmpty)
        expect(key(29, UInt32.max, repeatCount: UInt32.max).frame ==
            Data([0, 0, 0, 0, 0, 29, 0, 0, 3, 0xe8, 0, 0x77, 0x70, 0xff]))
        print("ScreenKeyboardInputTests passed (\(assertions) assertions)")
    }
}
