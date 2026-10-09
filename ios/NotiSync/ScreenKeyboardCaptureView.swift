import UIKit

/// A physical-key responder shared by the iPhone and iPad viewers. Deliberately not UIKeyInput:
/// becoming first responder must not open a software keyboard or create a hidden text editor.
class IOSScreenKeyboardCaptureView: UIView {
    var onKeyEvent: ((IOSScreenKeyEvent) -> Bool)?
    var onKeyboardFocusLost: (() -> Void)?
    var keyboardSessionID: ObjectIdentifier? {
        didSet {
            if keyboardSessionID != oldValue { releaseKeyboardInput() }
        }
    }
    var keyboardFocusRequest = 0 {
        didSet {
            if keyboardFocusRequest != oldValue { acquireKeyboardFocus() }
        }
    }
    var keyboardEnabled = false {
        didSet {
            guard keyboardEnabled != oldValue else { return }
            if keyboardEnabled { acquireKeyboardFocus() }
            else { relinquishKeyboardFocus() }
        }
    }
    private var handledPresses: Set<ObjectIdentifier> = []
    private var suppressedPresses: Set<ObjectIdentifier> = []

    override var canBecomeFirstResponder: Bool { keyboardEnabled }

    override init(frame: CGRect) {
        super.init(frame: frame)
        observeWindowFocus()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        observeWindowFocus()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { relinquishKeyboardFocus() }
        else { acquireKeyboardFocus() }
    }

    override func resignFirstResponder() -> Bool {
        let resigned = super.resignFirstResponder()
        if resigned { releaseKeyboardInput() }
        return resigned
    }

    func acquireKeyboardFocus() {
        guard keyboardEnabled, window?.isKeyWindow == true, !isFirstResponder else { return }
        var responder = next
        while let current = responder {
            if let controller = current as? UIViewController {
                guard controller.presentedViewController == nil, !controller.isBeingDismissed else { return }
                break
            }
            responder = current.next
        }
        becomeFirstResponder()
    }

    func relinquishKeyboardFocus() {
        if isFirstResponder { _ = resignFirstResponder() }
        releaseKeyboardInput()
    }

    private func releaseKeyboardInput() {
        onKeyboardFocusLost?()
        // Keep press identities until UIKit ends/cancels them, consuming releases from the old focus.
        suppressedPresses.formUnion(handledPresses)
        handledPresses.removeAll()
    }

    private func observeWindowFocus() {
        NotificationCenter.default.addObserver(self, selector: #selector(windowLostFocus(_:)),
                                               name: UIWindow.didResignKeyNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(windowGainedFocus(_:)),
                                               name: UIWindow.didBecomeKeyNotification, object: nil)
    }

    @objc private func windowLostFocus(_ notification: Notification) {
        guard notification.object as? UIWindow === window else { return }
        relinquishKeyboardFocus()
    }

    @objc private func windowGainedFocus(_ notification: Notification) {
        guard notification.object as? UIWindow === window else { return }
        acquireKeyboardFocus()
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var unhandled = presses
        guard keyboardEnabled, isFirstResponder else {
            super.pressesBegan(presses, with: event)
            return
        }
        // A set has no order. Dispatch modifiers first when UIKit groups them with another key.
        let ordered = presses.sorted {
            let left = $0.key?.keyCode.rawValue ?? 0
            let right = $1.key?.keyCode.rawValue ?? 0
            let leftModifier = (0xe0...0xe7).contains(left)
            let rightModifier = (0xe0...0xe7).contains(right)
            return leftModifier == rightModifier ? left < right : leftModifier
        }
        for press in ordered {
            // A new began starts a new lifecycle, even if UIKit reused a prior object's address.
            suppressedPresses.remove(ObjectIdentifier(press))
            guard let key = press.key else { continue }
            if onKeyEvent?(keyEvent(key, phase: .down)) == true {
                handledPresses.insert(ObjectIdentifier(press))
                unhandled.remove(press)
            }
        }
        if !unhandled.isEmpty { super.pressesBegan(unhandled, with: event) }
    }

    override func pressesChanged(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        // Changed is an analog-value update, not an auto-repeat signal.
        let unhandled = presses.filter {
            !handledPresses.contains(ObjectIdentifier($0)) && !suppressedPresses.contains(ObjectIdentifier($0))
        }
        if !unhandled.isEmpty { super.pressesChanged(unhandled, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let unhandled = finish(presses, phase: .up)
        if !unhandled.isEmpty { super.pressesEnded(unhandled, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let unhandled = finish(presses, phase: .cancelled)
        if !unhandled.isEmpty { super.pressesCancelled(unhandled, with: event) }
    }

    private func finish(_ presses: Set<UIPress>, phase: IOSScreenKeyEvent.Phase) -> Set<UIPress> {
        var unhandled = presses
        for press in presses {
            if suppressedPresses.remove(ObjectIdentifier(press)) != nil {
                unhandled.remove(press)
                continue
            }
            guard handledPresses.remove(ObjectIdentifier(press)) != nil else { continue }
            if let key = press.key { _ = onKeyEvent?(keyEvent(key, phase: phase)) }
            unhandled.remove(press)
        }
        return unhandled
    }

    private func keyEvent(_ key: UIKey, phase: IOSScreenKeyEvent.Phase) -> IOSScreenKeyEvent {
        IOSScreenKeyEvent(phase: phase, usage: key.keyCode.rawValue, characters: key.characters,
                          modifiers: IOSScreenKeyModifiers(rawValue: Int(key.modifierFlags.rawValue)))
    }
}
