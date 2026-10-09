import Foundation
import CoreGraphics

nonisolated struct IOSVirtualDisplayUnauthorized: LocalizedError, Sendable {
    let detail: String
    var errorDescription: String? { detail }
}

nonisolated enum IOSScreenDisplayNegotiation {
    /// Called after validating the sender, session binding, protocol version, and freshness.
    static func permitsFallback(status: ScreenMirrorSync, requestedVirtualDisplay: Bool,
                                connected: Bool, locallyCancelled: Bool) -> Bool {
        status.action == .STATUS && status.status == .VIRTUAL_DISPLAY_UNAUTHORIZED &&
            requestedVirtualDisplay && !connected && !locallyCancelled
    }

    /// Only an explicit, authenticated initial VD denial permits a fresh physical request.
    static func open<Session>(
        virtualDisplay: ScreenVirtualDisplay?,
        attempt: (ScreenVirtualDisplay?) async throws -> Session
    ) async throws -> Session {
        do {
            return try await attempt(virtualDisplay)
        } catch is IOSVirtualDisplayUnauthorized where virtualDisplay != nil {
            try Task.checkCancellation()
            return try await attempt(nil)
        }
    }
}

nonisolated enum IOSScreenVirtualDisplaySizing {
    /// The caller supplies the chosen video viewport, in UIKit points.
    static func forViewport(size: CGSize, displayScale: CGFloat) -> ScreenVirtualDisplay? {
        guard size.width.isFinite, size.height.isFinite, displayScale.isFinite,
              size.width > 0, size.height > 0, displayScale > 0 else { return nil }
        let width = Double(size.width * displayScale)
        let height = Double(size.height * displayScale)
        guard width.isFinite, height.isFinite, width > 0, height > 0,
              (width * height).isFinite else { return nil }
        let scale = min(
            max(1, 240 / min(width, height)),
            min(4096 / max(width, height), sqrt(8_388_608 / (width * height)))
        )
        let density = 160 * Double(displayScale) * scale
        guard scale.isFinite, density.isFinite else { return nil }
        // One Android dp corresponds to one viewer point, including when resolution is capped.
        return ScreenVirtualDisplay(
            width: max(240, min(4096, Int(width * scale))),
            height: max(240, min(4096, Int(height * scale))),
            densityDpi: Int(max(120, min(640, density.rounded())))
        )
    }

    static func resizeFrame(_ display: ScreenVirtualDisplay) -> Data? {
        guard display.hasValidSize else { return nil }
        var bytes: [UInt8] = [68]
        for value in [display.width, display.height, display.densityDpi] {
            bytes.append(UInt8(value >> 8))
            bytes.append(UInt8(value & 0xff))
        }
        return Data(bytes)
    }
}

/// Keeps the displayed video area and the requested Android display geometry in agreement.
nonisolated struct IOSScreenViewportLayout: Equatable {
    let size: CGSize
    let ignoresHorizontalInsets: Bool
    let ignoresVerticalInsets: Bool

    init(safeAreaSize: CGSize, horizontalInsets: CGFloat, verticalInsets: CGFloat, fullScreen: Bool) {
        let windowWidth = safeAreaSize.width + max(0, horizontalInsets)
        let windowHeight = safeAreaSize.height + max(0, verticalInsets)
        ignoresHorizontalInsets = fullScreen || windowWidth > windowHeight
        ignoresVerticalInsets = fullScreen
        size = safeAreaSize.width > 0 && safeAreaSize.height > 0
            ? CGSize(width: ignoresHorizontalInsets ? windowWidth : safeAreaSize.width,
                     height: ignoresVerticalInsets ? windowHeight : safeAreaSize.height)
            : .zero
    }
}
