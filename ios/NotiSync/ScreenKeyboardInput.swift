import Foundation

/// UIKit modifier flags, kept independent of UIKit so translation and wire frames can be tested.
nonisolated struct IOSScreenKeyModifiers: OptionSet, Sendable {
    let rawValue: Int
    static let capsLock = Self(rawValue: 1 << 16)
    static let shift = Self(rawValue: 1 << 17)
    static let control = Self(rawValue: 1 << 18)
    static let alternate = Self(rawValue: 1 << 19)
    static let command = Self(rawValue: 1 << 20)
    // UIKit's numericPad flag describes a key, not the Num Lock state.
}

nonisolated struct IOSScreenKeyEvent: Sendable {
    enum Phase: Sendable { case down, up, cancelled }
    let phase: Phase
    let usage: Int
    let characters: String
    let modifiers: IOSScreenKeyModifiers
}

nonisolated enum IOSScreenKeyboardCommand: Equatable, Sendable {
    case key(action: UInt8, code: Int32, repeatCount: UInt32, metaState: UInt32)
    case text(String)

    var frame: Data {
        var bytes: [UInt8]
        switch self {
        case let .key(action, code, repeatCount, metaState):
            bytes = [0, action]
            for value in [UInt32(bitPattern: code), min(repeatCount, 1_000), metaState & 0x0077_70ff] {
                bytes += Self.bigEndian(value)
            }
        case let .text(text):
            let textBytes = Array(text.utf8)
            guard !textBytes.isEmpty, textBytes.count <= 300 else { return Data() }
            bytes = [1] + Self.bigEndian(UInt32(textBytes.count)) + textBytes
        }
        return Data(bytes)
    }

    private static func bigEndian(_ value: UInt32) -> [UInt8] {
        [24, 16, 8, 0].map { UInt8(truncatingIfNeeded: value >> $0) }
    }
}

/// Physical keys use USB HID usages; printable text retains the viewer's keyboard layout.
/// This does not implement an IME or extend the source's Android character-map text injection.
nonisolated struct IOSScreenKeyboardInput {
    private struct HeldKey {
        let usage: Int
        // nil means text: consume its release without sending a second character.
        let remoteCode: Int32?
        var repeatCount: UInt32 = 0
    }
    private var heldKeys: [HeldKey] = []

    /// nil lets UIKit handle the event; an empty array consumes it without a wire message.
    mutating func handle(_ event: IOSScreenKeyEvent) -> [IOSScreenKeyboardCommand]? {
        let index = heldKeys.firstIndex { $0.usage == event.usage }
        switch event.phase {
        case .up, .cancelled:
            guard let index else { return nil }
            let held = heldKeys.remove(at: index)
            guard let code = held.remoteCode else { return [] }
            return [.key(action: 1, code: code, repeatCount: 0,
                         metaState: event.phase == .cancelled ? 0 : metaState(event.modifiers))]
        case .down:
            let shortcut = !event.modifiers.intersection([.control, .alternate, .command]).isEmpty
            if let index {
                // Keep the route chosen on DOWN even when a modifier changes before release.
                if let code = heldKeys[index].remoteCode {
                    heldKeys[index].repeatCount = min(heldKeys[index].repeatCount + 1, 1_000)
                    return [.key(action: 0, code: code, repeatCount: heldKeys[index].repeatCount,
                                 metaState: metaState(event.modifiers))]
                }
                return shortcut ? [] : textCommands(event.characters)
            }
            let code = Self.androidKeyCode(for: event.usage)
            let text = textCommands(event.characters)
            if let code, shortcut || Self.isRawKey(event.usage) || text == nil {
                heldKeys.append(HeldKey(usage: event.usage, remoteCode: code))
                return [.key(action: 0, code: code, repeatCount: 0, metaState: metaState(event.modifiers))]
            }
            // Unsupported shortcuts must never become ordinary inserted text.
            guard !shortcut, let text else { return nil }
            heldKeys.append(HeldKey(usage: event.usage, remoteCode: nil))
            return text
        }
    }

    mutating func releaseAll() -> [IOSScreenKeyboardCommand] {
        defer { heldKeys.removeAll() }
        return heldKeys.reversed().compactMap { held in
            held.remoteCode.map { .key(action: 1, code: $0, repeatCount: 0, metaState: 0) }
        }
    }

    private func textCommands(_ text: String) -> [IOSScreenKeyboardCommand]? {
        guard !text.isEmpty, text.utf8.count <= 300,
              text.unicodeScalars.allSatisfy({
                  !CharacterSet.controlCharacters.contains($0) && !(0xf700...0xf8ff).contains($0.value)
              }) else { return nil }
        return [.text(text)]
    }

    private func metaState(_ flags: IOSScreenKeyModifiers) -> UInt32 {
        var state: UInt32 = 0
        for (flag, mask) in [(IOSScreenKeyModifiers.shift, UInt32(0x1)), (.alternate, 0x2),
                             (.control, 0x1000), (.command, 0x10000), (.capsLock, 0x100000)] {
            if flags.contains(flag) { state |= mask }
        }
        // Only add side-specific bits when UIKit still reports that modifier as active.
        for held in heldKeys {
            switch held.usage {
            case 0xe0 where flags.contains(.control): state |= 0x2000
            case 0xe4 where flags.contains(.control): state |= 0x4000
            case 0xe1 where flags.contains(.shift): state |= 0x40
            case 0xe5 where flags.contains(.shift): state |= 0x80
            case 0xe2 where flags.contains(.alternate): state |= 0x10
            case 0xe6 where flags.contains(.alternate): state |= 0x20
            case 0xe3 where flags.contains(.command): state |= 0x20000
            case 0xe7 where flags.contains(.command): state |= 0x40000
            default: break
            }
        }
        return state
    }

    private static func isRawKey(_ usage: Int) -> Bool {
        // Navigation, locks, function keys, numpad Enter and left/right modifiers.
        (0x28...0x2b).contains(usage) || (0x39...0x53).contains(usage) || usage == 0x58 ||
            (0xe0...0xe7).contains(usage)
    }

    /// Android KeyEvent values accepted by the source's ControlKeyPolicy; no capability gate.
    static func androidKeyCode(for usage: Int) -> Int32? {
        switch usage {
        case 0x04...0x1d: return Int32(usage - 0x04 + 29) // A–Z
        case 0x1e...0x26: return Int32(usage - 0x1e + 8) // 1–9
        case 0x27: return 7 // 0
        case 0x28: return 66 // Enter
        case 0x29: return 111 // Escape (not Android Back)
        case 0x2a: return 67 // Backspace
        case 0x2b: return 61 // Tab
        case 0x2c: return 62 // Space
        case 0x2d: return 69 // - _
        case 0x2e: return 70 // = +
        case 0x2f: return 71 // [ {
        case 0x30: return 72 // ] }
        case 0x31, 0x32, 0x64: return 73 // Backslash / ISO variants
        case 0x33: return 74 // ; :
        case 0x34: return 75 // ' "
        case 0x35: return 68 // ` ~
        case 0x36: return 55 // , <
        case 0x37: return 56 // . >
        case 0x38: return 76 // / ?
        case 0x39: return 115 // Caps Lock
        case 0x3a...0x45: return Int32(usage - 0x3a + 131) // F1–F12
        case 0x46: return 120 // Print Screen
        case 0x47: return 116 // Scroll Lock
        case 0x48: return 121 // Pause
        case 0x49: return 124 // Insert
        case 0x4a: return 122 // Home (move cursor)
        case 0x4b: return 92 // Page Up
        case 0x4c: return 112 // Forward Delete
        case 0x4d: return 123 // End
        case 0x4e: return 93 // Page Down
        case 0x4f: return 22 // Right
        case 0x50: return 21 // Left
        case 0x51: return 20 // Down
        case 0x52: return 19 // Up
        case 0x53: return 143 // Num Lock
        case 0x54: return 154 // Numpad /
        case 0x55: return 155 // Numpad *
        case 0x56: return 156 // Numpad -
        case 0x57: return 157 // Numpad +
        case 0x58: return 160 // Numpad Enter
        case 0x59...0x61: return Int32(usage - 0x59 + 145) // Numpad 1–9
        case 0x62: return 144 // Numpad 0
        case 0x63: return 158 // Numpad .
        case 0x65, 0x76: return 82 // Application / Menu
        case 0x67, 0x86: return 161 // Numpad =
        case 0x7b: return 277 // Cut
        case 0x7c: return 278 // Copy
        case 0x7d: return 279 // Paste
        case 0x80: return 24 // Volume Up, if delivered by iOS
        case 0x81: return 25 // Volume Down
        case 0x85: return 159 // Numpad ,
        case 0xe0: return 113 // Left Ctrl
        case 0xe1: return 59 // Left Shift
        case 0xe2: return 57 // Left Alt / Option
        case 0xe3: return 117 // Left Meta / Command / Windows
        case 0xe4: return 114 // Right Ctrl
        case 0xe5: return 60 // Right Shift
        case 0xe6: return 58 // Right Alt / Option
        case 0xe7: return 118 // Right Meta / Command / Windows
        default: return nil
        }
    }
}
