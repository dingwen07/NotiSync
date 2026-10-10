import Foundation
import CoreGraphics

// Unused by these tests; lets the native screen DTOs compile without unrelated protocol adapters.
nonisolated struct SshAgentSync: Sendable {}
nonisolated struct HotspotSync: Sendable {}

@main
struct ScreenVirtualDisplayTests {
    static func main() async throws {
        let portraitLayout = IOSScreenViewportLayout(safeAreaSize: CGSize(width: 393, height: 759),
                                                     horizontalInsets: 0, verticalInsets: 93, fullScreen: false)
        precondition(portraitLayout.size == CGSize(width: 393, height: 759))
        precondition(!portraitLayout.ignoresHorizontalInsets && !portraitLayout.ignoresVerticalInsets)
        let landscapeLayout = IOSScreenViewportLayout(safeAreaSize: CGSize(width: 734, height: 372),
                                                      horizontalInsets: 118, verticalInsets: 21, fullScreen: false)
        precondition(landscapeLayout.size == CGSize(width: 852, height: 372))
        precondition(landscapeLayout.ignoresHorizontalInsets && !landscapeLayout.ignoresVerticalInsets)
        let fullLandscape = IOSScreenViewportLayout(safeAreaSize: CGSize(width: 734, height: 372),
                                                    horizontalInsets: 118, verticalInsets: 21, fullScreen: true)
        precondition(fullLandscape.size == CGSize(width: 852, height: 393))
        precondition(fullLandscape.ignoresHorizontalInsets && fullLandscape.ignoresVerticalInsets)
        let fullPortrait = IOSScreenViewportLayout(safeAreaSize: CGSize(width: 393, height: 759),
                                                   horizontalInsets: 0, verticalInsets: 93, fullScreen: true)
        precondition(fullPortrait.size == CGSize(width: 393, height: 852))
        // A narrow iPad window uses its own shape rather than the device's orientation.
        let padWindow = IOSScreenViewportLayout(safeAreaSize: CGSize(width: 507, height: 954),
                                                horizontalInsets: 12, verticalInsets: 70, fullScreen: false)
        precondition(padWindow.size == CGSize(width: 507, height: 954) && !padWindow.ignoresHorizontalInsets)
        let fullPadWindow = IOSScreenViewportLayout(safeAreaSize: CGSize(width: 507, height: 954),
                                                    horizontalInsets: 12, verticalInsets: 70, fullScreen: true)
        precondition(fullPadWindow.size == CGSize(width: 519, height: 1024))
        let fullLandscapeDisplay = IOSScreenVirtualDisplaySizing.forViewport(size: fullLandscape.size, displayScale: 3)!
        precondition(fullLandscapeDisplay.width == 2556 && fullLandscapeDisplay.height == 1179)
        let landscapeDisplay = IOSScreenVirtualDisplaySizing.forViewport(size: landscapeLayout.size, displayScale: 3)!
        precondition(landscapeDisplay.width == 2556 && landscapeDisplay.height == 1116)

        // Sizes are the live safe-area viewport in points, not the physical screen bounds.
        let phone = IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: 393, height: 759), displayScale: 3)!
        precondition(phone.width == 1179 && phone.height == 2277 && phone.densityDpi == 480)
        let landscape = IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: 734, height: 372), displayScale: 3)!
        precondition(landscape.width == 2202 && landscape.height == 1116)
        let pad = IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: 1024, height: 1322), displayScale: 2)!
        precondition(pad.width == 2048 && pad.height == 2644 && pad.densityDpi == 320)
        let split = IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: 507, height: 954), displayScale: 2)!
        precondition(split.width == 1014 && split.height == 1908 && split.densityDpi == 320)
        let resized = IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: 695, height: 954), displayScale: 2)!
        precondition(resized != split && resized.height == split.height)

        for size in [CGSize(width: 3000, height: 2000), CGSize(width: 2000, height: 3000),
                     CGSize(width: 320, height: 280), CGSize(width: 1200, height: 900)] {
            let display = IOSScreenVirtualDisplaySizing.forViewport(size: size, displayScale: 3)!
            precondition(display.hasValidSize)
            precondition(abs(Double(display.width) / Double(display.height) - size.width / size.height) < 0.01)
            // Pixel and DPI scaling preserve point-sized UI within integer rounding.
            precondition(abs(Double(display.width) * 160 / Double(display.densityDpi) - size.width) < 10)
        }
        precondition(IOSScreenVirtualDisplaySizing.forViewport(size: .zero, displayScale: 3) == nil)
        precondition(IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: CGFloat.infinity, height: 500), displayScale: 2) == nil)
        precondition(IOSScreenVirtualDisplaySizing.forViewport(size: CGSize(width: 500, height: 500), displayScale: 0) == nil)

        let frame = IOSScreenVirtualDisplaySizing.resizeFrame(ScreenVirtualDisplay(width: 1080, height: 1920, densityDpi: 320))
        precondition(frame == Data([68, 0x04, 0x38, 0x07, 0x80, 0x01, 0x40]))
        precondition(IOSScreenVirtualDisplaySizing.resizeFrame(ScreenVirtualDisplay(width: 4096, height: 4096, densityDpi: 320)) == nil)
        precondition(IOSScreenVirtualDisplaySizing.resizeFrame(ScreenVirtualDisplay(width: 239, height: 1000, densityDpi: 320)) == nil)

        var status = ScreenMirrorSync(action: .STATUS, protocolVersion: 2, sessionId: "session",
                                      requesterPeerId: "viewer", sourcePeerId: "source", issuedAt: 1,
                                      status: .VIRTUAL_DISPLAY_UNAUTHORIZED)
        func permits(virtual: Bool = true, connected: Bool = false, cancelled: Bool = false) -> Bool {
            IOSScreenDisplayNegotiation.permitsFallback(status: status, requestedVirtualDisplay: virtual,
                                                       connected: connected, locallyCancelled: cancelled)
        }
        precondition(permits())
        precondition(!permits(virtual: false) && !permits(connected: true) && !permits(cancelled: true))
        for action in [ScreenMirrorAction.REQUEST, .END, .CANCEL] {
            status.action = action
            precondition(!permits())
        }
        status.action = .STATUS
        for failure in [ScreenMirrorStatus.UNAUTHORIZED, .BUSY, .TRANSPORT_FAILED, .CODEC_START_FAILED, .ENDED] {
            status.status = failure
            precondition(!permits())
        }

        var requests: [ScreenVirtualDisplay?] = []
        let result = try await IOSScreenDisplayNegotiation.open(virtualDisplay: phone) { display in
            requests.append(display)
            if display != nil { throw IOSVirtualDisplayUnauthorized(detail: "denied") }
            return "physical"
        }
        precondition(result == "physical" && requests.count == 2 && requests[0] == phone && requests[1] == nil)

        requests = []
        _ = try await IOSScreenDisplayNegotiation.open(virtualDisplay: phone) { display in
            requests.append(display)
            return "virtual"
        }
        precondition(requests.count == 1 && requests[0] == phone)

        enum Failure: Error { case unauthorized, transport }
        for failure in [Failure.unauthorized, .transport] {
            requests = []
            do {
                let _: String = try await IOSScreenDisplayNegotiation.open(virtualDisplay: phone) { display in
                    requests.append(display)
                    throw failure
                }
                preconditionFailure("Unrelated errors must propagate")
            } catch is Failure { }
            precondition(requests.count == 1)
        }
        requests = []
        do {
            let _: String = try await IOSScreenDisplayNegotiation.open(virtualDisplay: phone) { display in
                requests.append(display)
                throw IOSVirtualDisplayUnauthorized(detail: "denied again")
            }
            preconditionFailure("Physical failure must propagate without another retry")
        } catch is IOSVirtualDisplayUnauthorized { }
        precondition(requests.count == 2)

        requests = []
        do {
            let _: String = try await IOSScreenDisplayNegotiation.open(virtualDisplay: nil) { display in
                requests.append(display)
                throw IOSVirtualDisplayUnauthorized(detail: "unexpected status")
            }
            preconditionFailure("Physical requests cannot trigger fallback")
        } catch is IOSVirtualDisplayUnauthorized { }
        precondition(requests.count == 1)

        let cancelled = Task {
            var attempts = 0
            do {
                let _: String = try await IOSScreenDisplayNegotiation.open(virtualDisplay: phone) { _ in
                    attempts += 1
                    withUnsafeCurrentTask { $0?.cancel() }
                    throw IOSVirtualDisplayUnauthorized(detail: "denied while closing")
                }
                preconditionFailure("Cancellation must stop fallback")
            } catch is CancellationError { }
            precondition(attempts == 1)
        }
        try await cancelled.value
        print("Passed iOS virtual-display sizing, resize framing, and permission-fallback checks.")
    }
}
