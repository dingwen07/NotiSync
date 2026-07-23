import AVFoundation
import AVKit
import Combine
import CoreMedia
import Foundation
import SwiftUI
import UIKit

fileprivate nonisolated struct IOSScreenTouchSample: Sendable {
    let action: UInt8
    let pointerId: UInt64
    let location: CGPoint
    let pressure: Double
}

fileprivate nonisolated struct IOSScreenTouchCommand: Sendable {
    let action: UInt8
    let pointerId: UInt64
    let x: Int
    let y: Int
    let sourceWidth: Int
    let sourceHeight: Int
    let pressure: Double
}

actor IOSScreenControlWriter {
    private let connection: IOSScreenWireConnection

    init(connection: IOSScreenWireConnection) { self.connection = connection }

    func sendKeyPress(_ keyCode: Int32) async throws {
        var frames = Data()
        frames.append(keyFrame(action: 0, keyCode: keyCode))
        frames.append(keyFrame(action: 1, keyCode: keyCode))
        try await connection.send(frames)
    }

    func sendText(_ text: String) async throws {
        let bytes = Data(text.utf8)
        guard !bytes.isEmpty, bytes.count <= 300 else { return }
        var frame = Data([1])
        frame.appendScreenUInt32(UInt32(bytes.count))
        frame.append(bytes)
        try await connection.send(frame)
    }

    func togglePower() async throws { try await connection.send(Data([64])) }

    func setVideoVisible(_ visible: Bool) async throws {
        try await connection.send(Data([65, visible ? 1 : 0]))
    }

    /// A rapid hidden→visible transition forces the source to begin a fresh codec session while keeping
    /// both authenticated channels alive. The new session includes codec config and an IDR frame.
    func restartVideo() async throws {
        try await connection.send(Data([65, 0, 65, 1]))
    }

    fileprivate func sendTouches(_ touches: [IOSScreenTouchCommand]) async throws {
        var frames = Data()
        for touch in touches {
            guard touch.action <= 3,
                  touch.sourceWidth > 0, touch.sourceWidth <= Int(UInt16.max),
                  touch.sourceHeight > 0, touch.sourceHeight <= Int(UInt16.max) else { continue }
            frames.append(touchFrame(touch))
        }
        guard !frames.isEmpty else { return }
        try await connection.send(frames)
    }

    private func touchFrame(_ touch: IOSScreenTouchCommand) -> Data {
        let fixedPressure: UInt16
        if touch.pressure <= 0 { fixedPressure = 0 }
        else if touch.pressure >= 1 { fixedPressure = UInt16.max }
        else { fixedPressure = UInt16(min(Int(UInt16.max), Int(touch.pressure * 65_536))) }
        var frame = Data([2, touch.action])
        frame.appendScreenUInt64(touch.pointerId)
        frame.appendScreenUInt32(UInt32(max(0, min(touch.sourceWidth - 1, touch.x))))
        frame.appendScreenUInt32(UInt32(max(0, min(touch.sourceHeight - 1, touch.y))))
        frame.appendScreenUInt16(UInt16(touch.sourceWidth))
        frame.appendScreenUInt16(UInt16(touch.sourceHeight))
        frame.appendScreenUInt16(fixedPressure)
        frame.appendScreenUInt32(0) // action button
        frame.appendScreenUInt32(0) // buttons
        return frame
    }

    private func keyFrame(action: UInt8, keyCode: Int32) -> Data {
        var frame = Data([0, action])
        frame.appendScreenUInt32(UInt32(bitPattern: keyCode))
        frame.appendScreenUInt32(0) // repeat
        frame.appendScreenUInt32(0) // meta state
        return frame
    }
}

/// Incremental reader/renderer for the pinned scrcpy v4.1 H.264 framing used by screen protocol v1.
nonisolated final class IOSScreenVideoDecoder: @unchecked Sendable {
    private let connection: IOSScreenWireConnection
    private let displayLayer: AVSampleBufferDisplayLayer
    private let onDimensions: @MainActor @Sendable (CGSize) -> Void
    private let onFailure: @MainActor @Sendable (Error) -> Void
    private var task: Task<Void, Never>?
    private var formatDescription: CMVideoFormatDescription?

    init(
        connection: IOSScreenWireConnection,
        displayLayer: AVSampleBufferDisplayLayer,
        onDimensions: @escaping @MainActor @Sendable (CGSize) -> Void,
        onFailure: @escaping @MainActor @Sendable (Error) -> Void
    ) {
        self.connection = connection
        self.displayLayer = displayLayer
        self.onDimensions = onDimensions
        self.onFailure = onFailure
    }

    func start() {
        guard task == nil else { return }
        task = Task(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            do { try await self.decode() }
            catch is CancellationError { }
            catch { await self.onFailure(error) }
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
        connection.cancel()
        displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
    }

    private func decode() async throws {
        let preamble = try await connection.readExactly(16)
        var initial = IOSVideoDataReader(preamble)
        guard try initial.uint32() == 0x68323634 else {
            throw IOSScreenTransportError.unsupportedStream("The source device did not start an H.264 stream.")
        }
        let flags = try initial.uint32()
        guard flags & 0x80000000 != 0, flags & 0x7ffffffe == 0 else {
            throw IOSScreenTransportError.unsupportedStream("The source device sent invalid stream metadata.")
        }
        try await updateDimensions(width: Int(initial.uint32()), height: Int(initial.uint32()))

        while !Task.isCancelled {
            let header = try await connection.readExactly(12)
            var reader = IOSVideoDataReader(header)
            let firstWord = try reader.uint32()
            if firstWord & 0x80000000 != 0 {
                guard firstWord & 0x7ffffffe == 0 else {
                    throw IOSScreenTransportError.unsupportedStream("The source device sent invalid resize metadata.")
                }
                try await updateDimensions(width: Int(reader.uint32()), height: Int(reader.uint32()))
                formatDescription = nil
                await displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true)
                continue
            }
            let secondWord = try reader.uint32()
            let ptsAndFlags = (UInt64(firstWord) << 32) | UInt64(secondWord)
            let packetSize = Int(try reader.uint32())
            guard packetSize > 0, packetSize <= 16 * 1024 * 1024 else {
                throw IOSScreenTransportError.unsupportedStream("The source device sent an oversized video packet.")
            }
            let packet = try await connection.readExactly(packetSize)
            let configFlag = UInt64(1) << 62
            if ptsAndFlags & configFlag != 0 {
                guard ptsAndFlags == configFlag else {
                    throw IOSScreenTransportError.unsupportedStream("The source device sent invalid codec metadata.")
                }
                formatDescription = try makeH264FormatDescription(config: packet)
            } else if let formatDescription {
                let ptsMask = (UInt64(1) << 61) - 1
                try enqueue(packet: packet, ptsUs: Int64(ptsAndFlags & ptsMask), format: formatDescription)
            }
        }
    }

    private func updateDimensions(width: Int, height: Int) async throws {
        guard width > 0, width <= 8_192, height > 0, height <= 8_192,
              Int64(width) * Int64(height) <= 16_000_000 else {
            throw IOSScreenTransportError.unsupportedStream("The source device sent invalid video dimensions.")
        }
        await onDimensions(CGSize(width: width, height: height))
    }

    private func makeH264FormatDescription(config: Data) throws -> CMVideoFormatDescription {
        let parameterSets = h264ParameterSets(config)
        guard let sps = parameterSets.first(where: { $0.first.map { $0 & 0x1f == 7 } == true }),
              let pps = parameterSets.first(where: { $0.first.map { $0 & 0x1f == 8 } == true }) else {
            throw IOSScreenTransportError.unsupportedStream("The source H.264 encoder sent no SPS/PPS.")
        }
        var description: CMFormatDescription?
        let status: OSStatus = sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                var pointers = [
                    spsBytes.baseAddress!.assumingMemoryBound(to: UInt8.self),
                    ppsBytes.baseAddress!.assumingMemoryBound(to: UInt8.self),
                ]
                var sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: &pointers,
                    parameterSetSizes: &sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &description
                )
            }
        }
        guard status == noErr, let description else {
            throw IOSScreenTransportError.unsupportedStream("iOS could not configure the H.264 decoder.")
        }
        return description
    }

    private func enqueue(packet: Data, ptsUs: Int64, format: CMVideoFormatDescription) throws {
        let avcc = annexBToAVCC(packet)
        var blockBuffer: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == kCMBlockBufferNoErr, let blockBuffer else {
            throw IOSScreenTransportError.unsupportedStream("iOS could not allocate a video buffer.")
        }
        status = avcc.withUnsafeBytes { bytes in
            CMBlockBufferReplaceDataBytes(
                with: bytes.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }
        guard status == kCMBlockBufferNoErr else {
            throw IOSScreenTransportError.unsupportedStream("iOS could not fill a video buffer.")
        }
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMTime(value: ptsUs, timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sample: CMSampleBuffer?
        var sampleSize = avcc.count
        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sample
        )
        guard status == noErr, let sample else {
            throw IOSScreenTransportError.unsupportedStream("iOS could not create a video sample.")
        }
        // DisplayImmediately is a per-sample attachment. Using CMSetAttachment here leaves the
        // remote presentation timestamp in control, which can make the renderer hold every frame
        // after the first when the two devices' clocks don't share an epoch.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(
                CFArrayGetValueAtIndex(attachments, 0),
                to: CFMutableDictionary.self
            )
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding { renderer.flush() }
        renderer.enqueue(sample)
    }
}

@MainActor
private final class IOSScreenPictureInPictureCoordinator: NSObject,
    AVPictureInPictureControllerDelegate,
    AVPictureInPictureSampleBufferPlaybackDelegate
{
    var onPossibleChange: ((Bool) -> Void)?
    var onActiveChange: ((Bool) -> Void)?
    var onFailure: ((Error) -> Void)?

    private var controller: AVPictureInPictureController!
    private var possibleObservation: NSKeyValueObservation?
    private var audioSessionIsActive = false
    private var shuttingDown = false

    var isPossible: Bool { controller.isPictureInPicturePossible }
    var isActive: Bool { controller.isPictureInPictureActive }

    init(displayLayer: AVSampleBufferDisplayLayer) {
        super.init()

        // The content source holds its playback delegate weakly, while the model retains this coordinator.
        let contentSource = AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: displayLayer,
            playbackDelegate: self
        )
        controller = AVPictureInPictureController(contentSource: contentSource)
        controller.delegate = self
        controller.requiresLinearPlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        possibleObservation = controller.observe(\.isPictureInPicturePossible, options: [.initial, .new]) {
            [weak self] controller, _ in
            let possible = controller.isPictureInPicturePossible
            Task { @MainActor [weak self] in
                self?.onPossibleChange?(possible)
            }
        }
    }

    func prepareForAutomaticStart() {
        shuttingDown = false
        try? activateAudioSession()
    }

    func start() {
        guard controller.isPictureInPicturePossible else { return }
        shuttingDown = false
        do {
            try activateAudioSession()
            controller.startPictureInPicture()
        } catch {
            onFailure?(error)
        }
    }

    func stop() {
        if controller.isPictureInPictureActive { controller.stopPictureInPicture() }
    }

    func shutdown() {
        shuttingDown = true
        if controller.isPictureInPictureActive { controller.stopPictureInPicture() }
        else { deactivateAudioSession() }
    }

    private func deactivateAudioSession() {
        guard audioSessionIsActive else { return }
        audioSessionIsActive = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func activateAudioSession() throws {
        guard !audioSessionIsActive else { return }
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
        try audioSession.setActive(true)
        audioSessionIsActive = true
    }

    func pictureInPictureControllerWillStartPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        guard !shuttingDown else { return }
        onActiveChange?(true)
    }

    func pictureInPictureControllerDidStartPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        guard !shuttingDown else {
            pictureInPictureController.stopPictureInPicture()
            return
        }
        onActiveChange?(true)
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError error: Error
    ) {
        onActiveChange?(false)
        if shuttingDown { deactivateAudioSession() }
        onFailure?(error)
    }

    func pictureInPictureControllerDidStopPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        onActiveChange?(false)
        if shuttingDown { deactivateAudioSession() }
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(true)
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        setPlaying playing: Bool
    ) {
        // Screen sharing is a live stream. Keep it playing and update the system transport controls.
        pictureInPictureController.invalidatePlaybackState()
    }

    func pictureInPictureControllerTimeRangeForPlayback(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> Bool {
        false
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions
    ) { }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime,
        completion: @escaping () -> Void
    ) {
        completion()
    }
}

@MainActor
final class IOSScreenMirrorPlayerModel: ObservableObject {
    @Published var dimensions = CGSize.zero
    @Published var status = "Preparing secure LAN session…"
    @Published var errorMessage: String?
    @Published var connected = false
    @Published var showsTextInput = false
    @Published var showsInputText = false
    @Published var inputText = ""
    @Published var zoomedOut = false
    @Published private(set) var pictureInPictureSupported = AVPictureInPictureController.isPictureInPictureSupported()
    @Published private(set) var pictureInPicturePossible = false
    @Published private(set) var pictureInPictureActive = false
    @Published var pictureInPictureErrorMessage: String?

    let displayLayer = AVSampleBufferDisplayLayer()
    private var pictureInPicture: IOSScreenPictureInPictureCoordinator?
    private var session: IOSScreenMirrorSession?
    private var decoder: IOSScreenVideoDecoder?
    private var control: IOSScreenControlWriter?
    private var activeTouchIds: Set<UInt64> = []
    private var lastTouchLocations: [UInt64: CGPoint] = [:]
    private var touchSendTask: Task<Void, Never>?
    private var videoVisibilityTask: Task<Void, Never>?
    private var relayFallbackTask: Task<Void, Never>?
    private var playbackSuspended = false
    private var appIsActive = true
    private var stopped = false
    private var pageClosed = false
    private var connectionMode: IOSScreenConnectionMode = .direct
    private var relayAvailableForSource = false
    private var attemptGeneration = 0

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
        guard pictureInPictureSupported else { return }
        let pictureInPicture = IOSScreenPictureInPictureCoordinator(displayLayer: displayLayer)
        pictureInPicture.onPossibleChange = { [weak self] possible in
            self?.pictureInPicturePossible = possible
        }
        pictureInPicture.onActiveChange = { [weak self] active in
            guard let self else { return }
            self.pictureInPictureActive = active
            self.reconcilePlaybackActivity()
        }
        pictureInPicture.onFailure = { [weak self] error in
            self?.pictureInPictureErrorMessage = error.localizedDescription
        }
        self.pictureInPicture = pictureInPicture
        pictureInPicturePossible = pictureInPicture.isPossible
    }

    func start(runtime: NotiSyncRuntime, sourceId: String, sourceName: String) async {
        guard !pageClosed else { return }
        attemptGeneration += 1
        let attempt = attemptGeneration
        stopped = false
        connected = false
        errorMessage = nil
        dimensions = .zero
        relayAvailableForSource = runtime.supportsScreenMirrorBrokerRelay(sourceId: sourceId)
        relayFallbackTask?.cancel()
        if connectionMode == .direct, relayAvailableForSource {
            relayFallbackTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                guard let self, !Task.isCancelled, !self.stopped,
                      self.attemptGeneration == attempt, !self.connected,
                      self.connectionMode == .direct else { return }
                self.relayFallbackTask = nil
                await self.switchToBrokerRelay(
                    runtime: runtime,
                    sourceId: sourceId,
                    sourceName: sourceName
                )
            }
        }
        status = connectionMode == .brokerRelay
            ? "Preparing secure broker Relay…"
            : "Preparing secure LAN session…"
        do {
            let session = try await runtime.openScreenMirror(
                sourceId: sourceId,
                sourceName: sourceName,
                connectionMode: connectionMode
            )
            guard !stopped, attemptGeneration == attempt else {
                session.cancelTransport()
                await runtime.endScreenMirror(sessionId: session.sessionId)
                return
            }
            relayFallbackTask?.cancel()
            relayFallbackTask = nil
            self.session = session
            control = IOSScreenControlWriter(connection: session.channels.control)
            connected = true
            pictureInPicture?.prepareForAutomaticStart()
            if playbackSuspended { queueVideoVisibility(false) }
            status = session.connectionMode == .brokerRelay
                ? "Connected through broker Relay"
                : "Connected over local network"
            let decoder = IOSScreenVideoDecoder(
                connection: session.channels.video,
                displayLayer: displayLayer,
                onDimensions: { [weak self] size in
                    guard let self, !self.stopped, self.attemptGeneration == attempt else { return }
                    self.dimensions = size
                },
                onFailure: { [weak self] error in
                    guard let self, !self.stopped, self.attemptGeneration == attempt else { return }
                    self.connected = false
                    if self.connectionMode == .direct, self.relayAvailableForSource {
                        self.status = "Local connection interrupted. Switching to broker Relay…"
                        Task { [weak self] in
                            await self?.switchToBrokerRelay(
                                runtime: runtime,
                                sourceId: sourceId,
                                sourceName: sourceName
                            )
                        }
                    } else {
                        self.errorMessage = error.localizedDescription
                    }
                }
            )
            self.decoder = decoder
            decoder.start()
        } catch {
            guard !stopped, attemptGeneration == attempt else { return }
            relayFallbackTask?.cancel()
            relayFallbackTask = nil
            if connectionMode == .direct, relayAvailableForSource,
               Self.isDirectTransportFailure(error) {
                await switchToBrokerRelay(
                    runtime: runtime,
                    sourceId: sourceId,
                    sourceName: sourceName
                )
                return
            }
            errorMessage = error.localizedDescription
            status = "Could not connect"
        }
    }

    func retry(runtime: NotiSyncRuntime, sourceId: String, sourceName: String) async {
        await tearDownTransport(runtime: runtime)
        guard !pageClosed else { return }
        await start(runtime: runtime, sourceId: sourceId, sourceName: sourceName)
    }

    private func switchToBrokerRelay(runtime: NotiSyncRuntime, sourceId: String, sourceName: String) async {
        guard relayAvailableForSource, connectionMode == .direct else { return }
        relayFallbackTask?.cancel()
        relayFallbackTask = nil
        connectionMode = .brokerRelay
        errorMessage = nil
        status = "Switching to broker Relay…"
        await retry(runtime: runtime, sourceId: sourceId, sourceName: sourceName)
    }

    private static func isDirectTransportFailure(_ error: Error) -> Bool {
        if error is IOSScreenTransportError { return true }
        if let runtimeError = error as? IOSScreenMirrorRuntimeError,
           case .transport = runtimeError { return true }
        return false
    }

    func stop(runtime: NotiSyncRuntime) async {
        pageClosed = true
        await tearDownTransport(runtime: runtime)
    }

    private func tearDownTransport(runtime: NotiSyncRuntime) async {
        guard !stopped else { return }
        attemptGeneration += 1
        stopped = true
        pictureInPicture?.shutdown()
        pictureInPictureActive = false
        decoder?.cancel()
        decoder = nil
        touchSendTask?.cancel()
        touchSendTask = nil
        videoVisibilityTask?.cancel()
        videoVisibilityTask = nil
        relayFallbackTask?.cancel()
        relayFallbackTask = nil
        session?.cancelTransport()
        await runtime.endScreenMirror(sessionId: session?.sessionId)
        session = nil
        control = nil
        activeTouchIds.removeAll()
        lastTouchLocations.removeAll()
        connected = false
    }

    func updatePlaybackActivity(isActive: Bool) {
        appIsActive = isActive
        reconcilePlaybackActivity()
    }

    private func reconcilePlaybackActivity() {
        let shouldPlay = appIsActive || pictureInPictureActive
        if shouldPlay {
            guard playbackSuspended else { return }
            playbackSuspended = false
            guard connected else { return }
            // A suspended AVSampleBufferDisplayLayer may explicitly require a flush. Always flush here,
            // then make the source emit a fresh session boundary/config/IDR instead of resuming interframes.
            displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true)
            queueVideoRestart()
            return
        }

        guard !playbackSuspended else { return }
        playbackSuspended = true
        activeTouchIds.removeAll()
        lastTouchLocations.removeAll()
        guard connected else { return }
        queueVideoVisibility(false)
    }

    private func queueVideoVisibility(_ visible: Bool) {
        guard let control else { return }
        let preceding = videoVisibilityTask
        videoVisibilityTask = Task {
            await preceding?.value
            guard !Task.isCancelled else { return }
            try? await control.setVideoVisible(visible)
        }
    }

    private func queueVideoRestart() {
        guard let control else { return }
        let preceding = videoVisibilityTask
        videoVisibilityTask = Task {
            await preceding?.value
            guard !Task.isCancelled else { return }
            try? await control.restartVideo()
        }
    }

    func sendKey(_ keyCode: Int32) {
        guard let control else { return }
        Task { try? await control.sendKeyPress(keyCode) }
    }

    func togglePower() {
        guard let control else { return }
        Task { try? await control.togglePower() }
    }

    func sendInputText(returnAfter: Bool = false) {
        let text = inputText
        let byteCount = Data(text.utf8).count
        guard byteCount <= 300, returnAfter || !text.isEmpty else { return }
        inputText = ""
        showsTextInput = false
        showsInputText = false
        guard let control else { return }
        Task {
            do {
                if !text.isEmpty { try await control.sendText(text) }
                if returnAfter { try await control.sendKeyPress(66) }
            } catch { }
        }
    }

    func beginTextInput() {
        inputText = ""
        showsInputText = false
        showsTextInput = true
    }

    func cancelTextInput() {
        inputText = ""
        showsInputText = false
        showsTextInput = false
    }

    func toggleDisplayZoom() {
        zoomedOut.toggle()
    }

    func togglePictureInPicture() {
        pictureInPictureErrorMessage = nil
        if pictureInPictureActive {
            pictureInPicture?.stop()
        } else {
            pictureInPicture?.start()
        }
    }

    fileprivate func handleTouches(_ samples: [IOSScreenTouchSample], in viewSize: CGSize) {
        guard let control, dimensions.width > 0, dimensions.height > 0 else { return }
        var commands: [IOSScreenTouchCommand] = []

        for sample in samples {
            switch sample.action {
            case 0: // ACTION_DOWN; scrcpy converts later fingers to ACTION_POINTER_DOWN.
                guard !activeTouchIds.contains(sample.pointerId),
                      let mapped = mappedTouch(sample.location, viewSize: viewSize, allowsOutside: false) else { continue }
                activeTouchIds.insert(sample.pointerId)
                lastTouchLocations[sample.pointerId] = mapped
                commands.append(touchCommand(for: sample, mapped: mapped, pressure: sample.pressure))
            case 2: // ACTION_MOVE
                guard activeTouchIds.contains(sample.pointerId),
                      let mapped = mappedTouch(sample.location, viewSize: viewSize, allowsOutside: true)
                        ?? lastTouchLocations[sample.pointerId] else { continue }
                lastTouchLocations[sample.pointerId] = mapped
                commands.append(touchCommand(for: sample, mapped: mapped, pressure: sample.pressure))
            case 1: // ACTION_UP; scrcpy converts non-final fingers to ACTION_POINTER_UP.
                guard activeTouchIds.remove(sample.pointerId) != nil else { continue }
                let mapped = mappedTouch(sample.location, viewSize: viewSize, allowsOutside: true)
                    ?? lastTouchLocations[sample.pointerId]
                lastTouchLocations.removeValue(forKey: sample.pointerId)
                if let mapped {
                    commands.append(touchCommand(for: sample, mapped: mapped, pressure: 0))
                }
            case 3: // ACTION_CANCEL terminates the whole remote gesture.
                guard let pointerId = activeTouchIds.first,
                      let mapped = lastTouchLocations[pointerId] else { continue }
                commands.append(IOSScreenTouchCommand(
                    action: 3,
                    pointerId: pointerId,
                    x: Int(mapped.x), y: Int(mapped.y),
                    sourceWidth: Int(dimensions.width), sourceHeight: Int(dimensions.height),
                    pressure: 0
                ))
                activeTouchIds.removeAll()
                lastTouchLocations.removeAll()
            default:
                continue
            }
        }
        guard !commands.isEmpty else { return }
        let precedingSend = touchSendTask
        touchSendTask = Task {
            await precedingSend?.value
            guard !Task.isCancelled else { return }
            try? await control.sendTouches(commands)
        }
    }

    private func touchCommand(
        for sample: IOSScreenTouchSample,
        mapped: CGPoint,
        pressure: Double
    ) -> IOSScreenTouchCommand {
        IOSScreenTouchCommand(
            action: sample.action,
            pointerId: sample.pointerId,
            x: Int(mapped.x), y: Int(mapped.y),
            sourceWidth: Int(dimensions.width), sourceHeight: Int(dimensions.height),
            pressure: pressure
        )
    }

    private func mappedTouch(_ point: CGPoint, viewSize: CGSize, allowsOutside: Bool) -> CGPoint? {
        guard dimensions.width > 0, dimensions.height > 0,
              viewSize.width > 0, viewSize.height > 0 else { return nil }
        let scale = min(viewSize.width / dimensions.width, viewSize.height / dimensions.height)
        let rendered = CGSize(width: dimensions.width * scale, height: dimensions.height * scale)
        let origin = CGPoint(x: (viewSize.width - rendered.width) / 2, y: (viewSize.height - rendered.height) / 2)
        let rect = CGRect(origin: origin, size: rendered)
        guard allowsOutside || rect.contains(point) else { return nil }
        let clampedX = min(rect.maxX.nextDown, max(rect.minX, point.x))
        let clampedY = min(rect.maxY.nextDown, max(rect.minY, point.y))
        return CGPoint(
            x: (clampedX - rect.minX) * dimensions.width / rect.width,
            y: (clampedY - rect.minY) * dimensions.height / rect.height
        )
    }
}

struct ScreenMirrorPlayerView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = IOSScreenMirrorPlayerModel()
    @FocusState private var inputIsFocused: Bool
    let sourceId: String
    let sourceName: String

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScreenMirrorVideoSurface(
                layer: model.displayLayer,
                sourceSize: model.dimensions,
                zoomedOut: model.zoomedOut,
                onTouches: { samples, size in model.handleTouches(samples, in: size) }
            )
                .allowsHitTesting(model.connected)
                .ignoresSafeArea()
            if !model.connected {
                VStack(spacing: 14) {
                    if let error = model.errorMessage {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.largeTitle)
                        Text(error).multilineTextAlignment(.center)
                        HStack(spacing: 12) {
                            Button("Cancel", role: .cancel) { dismiss() }
                                .screenMirrorNativeButton()
                            Button("Retry") {
                                Task {
                                    await model.retry(
                                        runtime: runtime,
                                        sourceId: sourceId,
                                        sourceName: sourceName
                                    )
                                }
                            }
                            .screenMirrorNativeButton(prominent: true)
                        }
                    } else {
                        ProgressView().tint(.white)
                        Text(runtime.screenMirrorPhase ?? model.status)
                            .multilineTextAlignment(.center)
                        Button("Cancel", role: .cancel) { dismiss() }
                            .screenMirrorNativeButton()
                    }
                }
                .foregroundStyle(.white)
                .padding(28)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 20))
                .padding()
            }

            VStack(spacing: 0) {
                viewerHeader
                Spacer(minLength: 0)
                if model.connected {
                    viewerControls
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear { model.updatePlaybackActivity(isActive: scenePhase != .background) }
        .onChange(of: scenePhase) { _, phase in
            model.updatePlaybackActivity(isActive: phase != .background)
        }
        .task { await model.start(runtime: runtime, sourceId: sourceId, sourceName: sourceName) }
        .onDisappear { Task { await model.stop(runtime: runtime) } }
        .alert(
            "Picture in Picture",
            isPresented: Binding(
                get: { model.pictureInPictureErrorMessage != nil },
                set: { if !$0 { model.pictureInPictureErrorMessage = nil } }
            )
        ) {
            Button("OK") { model.pictureInPictureErrorMessage = nil }
        } message: {
            Text(model.pictureInPictureErrorMessage ?? "Picture in Picture could not start.")
        }
        .sheet(isPresented: $model.showsTextInput) {
            NavigationStack {
                Form {
                    Section {
                        Group {
                            if model.showsInputText {
                                TextField("Text", text: $model.inputText)
                            } else {
                                SecureField("Text", text: $model.inputText)
                                    .textContentType(.password)
                            }
                        }
                        .focused($inputIsFocused)
                        .submitLabel(.send)
                        .onSubmit {
                            guard !model.inputText.isEmpty,
                                  Data(model.inputText.utf8).count <= 300 else { return }
                            model.sendInputText()
                        }

                        Toggle("Show input", isOn: $model.showsInputText)
                    } footer: {
                        Text("Send a maximum of 300 bytes at a time.")
                    }
                }
                .navigationTitle("Input")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", role: .cancel) { model.cancelTextInput() }
                    }
                    ToolbarItemGroup(placement: .confirmationAction) {
                        Button("Return") { model.sendInputText(returnAfter: true) }
                            .disabled(Data(model.inputText.utf8).count > 300)
                        Button("Send") { model.sendInputText() }
                            .disabled(model.inputText.isEmpty || Data(model.inputText.utf8).count > 300)
                    }
                }
            }
            .presentationDetents([.height(260)])
            .presentationDragIndicator(.visible)
            .onAppear { inputIsFocused = true }
            .onChange(of: model.showsInputText) { _, _ in inputIsFocused = true }
            .onDisappear {
                inputIsFocused = false
                model.cancelTextInput()
            }
        }
    }

    private var viewerHeader: some View {
        Group {
            if #available(iOS 26.0, *) {
                GlassEffectContainer(spacing: 10) { viewerHeaderContent }
            } else {
                viewerHeaderContent
            }
        }
        .foregroundStyle(.white)
    }

    private var viewerHeaderContent: some View {
        ZStack {
            viewerTitle
                // Keep the wider title capsule clear of the matching outer controls.
                .padding(.horizontal, 52)

            HStack {
                viewerControl("Close screen viewer", systemImage: "xmark") { dismiss() }
                Spacer()
                if model.connected {
                    viewerControl(
                        model.zoomedOut ? "Fill Screen" : "Zoom Out",
                        systemImage: model.zoomedOut
                            ? "plus.magnifyingglass"
                            : "minus.magnifyingglass"
                    ) { model.toggleDisplayZoom() }
                }
            }
        }
    }

    @ViewBuilder
    private var viewerTitle: some View {
        if #available(iOS 26.0, *) {
            viewerTitleContent
                .glassEffect(.regular, in: .capsule)
        } else {
            viewerTitleContent
                .background(.black.opacity(0.62), in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.16), lineWidth: 0.5))
        }
    }

    private var viewerTitleContent: some View {
        ZStack {
            Text(sourceName)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, model.connected && model.pictureInPictureSupported ? 42 : 14)

            if model.connected && model.pictureInPictureSupported {
                HStack {
                    Spacer()
                    Button { model.togglePictureInPicture() } label: {
                        Image(systemName: model.pictureInPictureActive ? "pip.exit" : "pip.enter")
                            .font(.subheadline.weight(.semibold))
                            .frame(width: 28, height: 28)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!model.pictureInPicturePossible && !model.pictureInPictureActive)
                    .opacity(model.pictureInPicturePossible || model.pictureInPictureActive ? 1 : 0.45)
                    .accessibilityLabel(
                        model.pictureInPictureActive
                            ? "Stop Picture in Picture"
                            : "Start Picture in Picture"
                    )
                }
                .padding(.trailing, 6)
            }
        }
        .frame(maxWidth: 280)
        .frame(height: 40)
    }

    private var viewerControls: some View {
        Group {
            if #available(iOS 26.0, *) {
                GlassEffectContainer(spacing: 6) { viewerControlButtons }
            } else {
                viewerControlButtons
            }
        }
    }

    private var viewerControlButtons: some View {
        HStack(spacing: 2) {
            viewerControl("Type", systemImage: "keyboard") { model.beginTextInput() }
            viewerControl("Back", systemImage: "chevron.backward") { model.sendKey(4) }
            viewerControl("Home", systemImage: "circle") { model.sendKey(3) }
            viewerControl("Recent Apps", systemImage: "square.on.square") { model.sendKey(187) }
            viewerControl("Power", systemImage: "power") { model.togglePower() }
        }
        .foregroundStyle(.white)
    }

    private func viewerControl(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3.weight(.medium))
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .screenMirrorNativeButton()
        .buttonBorderShape(.circle)
        .controlSize(.small)
        .accessibilityLabel(title)
    }

}

extension View {
    @ViewBuilder
    func screenMirrorNativeButton(prominent: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            if prominent {
                buttonStyle(.glassProminent)
            } else {
                buttonStyle(.glass)
            }
        } else if prominent {
            buttonStyle(.borderedProminent)
        } else {
            buttonStyle(.bordered)
        }
    }
}

private struct ScreenMirrorVideoSurface: UIViewRepresentable {
    let layer: AVSampleBufferDisplayLayer
    let sourceSize: CGSize
    let zoomedOut: Bool
    let onTouches: ([IOSScreenTouchSample], CGSize) -> Void

    func makeUIView(context: Context) -> ScreenMirrorLayerHostView {
        let view = ScreenMirrorLayerHostView()
        view.backgroundColor = .black
        view.hostedLayer = layer
        view.configure(sourceSize: sourceSize, zoomedOut: zoomedOut)
        view.onTouches = onTouches
        return view
    }

    func updateUIView(_ uiView: ScreenMirrorLayerHostView, context: Context) {
        uiView.hostedLayer = layer
        uiView.configure(sourceSize: sourceSize, zoomedOut: zoomedOut)
        uiView.onTouches = onTouches
    }
}

private final class ScreenMirrorLayerHostView: UIView {
    private static let zoomedOutScale: CGFloat = 0.76
    private static let zoomedOutVerticalOffset: CGFloat = 20
    private static let zoomDuration: CFTimeInterval = 0.24

    var onTouches: (([IOSScreenTouchSample], CGSize) -> Void)?
    private var pointerIds: [ObjectIdentifier: UInt64] = [:]
    private var nextPointerId: UInt64 = 0
    private var sourceSize = CGSize.zero
    private var zoomedOut = false
    private let videoContainerLayer = CALayer()

    var hostedLayer: AVSampleBufferDisplayLayer? {
        didSet {
            if oldValue !== hostedLayer { oldValue?.removeFromSuperlayer() }
            if let hostedLayer, hostedLayer.superlayer !== videoContainerLayer {
                hostedLayer.setAffineTransform(.identity)
                hostedLayer.cornerRadius = 0
                hostedLayer.borderWidth = 0
                hostedLayer.masksToBounds = false
                hostedLayer.isOpaque = true
                videoContainerLayer.addSublayer(hostedLayer)
            }
            setNeedsLayout()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isUserInteractionEnabled = true
        configureVideoContainer()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isMultipleTouchEnabled = true
        isUserInteractionEnabled = true
        configureVideoContainer()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let size = aspectFitSourceSize()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoContainerLayer.bounds = CGRect(origin: .zero, size: size)
        applyZoomState(to: videoContainerLayer, animated: false)
        if let hostedLayer {
            hostedLayer.bounds = videoContainerLayer.bounds
            hostedLayer.position = CGPoint(x: videoContainerLayer.bounds.midX, y: videoContainerLayer.bounds.midY)
        }
        CATransaction.commit()
    }

    func configure(sourceSize: CGSize, zoomedOut: Bool) {
        let sourceChanged = self.sourceSize != sourceSize
        if sourceChanged {
            self.sourceSize = sourceSize
            setNeedsLayout()
            layoutIfNeeded()
        }
        guard self.zoomedOut != zoomedOut else { return }
        self.zoomedOut = zoomedOut
        applyZoomState(to: videoContainerLayer, animated: true)
    }

    private func configureVideoContainer() {
        isOpaque = true
        layer.isOpaque = true
        videoContainerLayer.backgroundColor = UIColor.black.cgColor
        videoContainerLayer.contentsScale = UIScreen.main.scale
        videoContainerLayer.allowsEdgeAntialiasing = true
        videoContainerLayer.isOpaque = true
        videoContainerLayer.masksToBounds = true
        layer.addSublayer(videoContainerLayer)
    }

    private func aspectFitSourceSize() -> CGSize {
        guard bounds.width > 0, bounds.height > 0 else { return .zero }
        guard sourceSize.width > 0, sourceSize.height > 0 else { return bounds.size }
        let scale = min(bounds.width / sourceSize.width, bounds.height / sourceSize.height)
        return CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)
    }

    private func applyZoomState(to container: CALayer, animated: Bool) {
        let scale = zoomedOut ? Self.zoomedOutScale : 1
        let targetPosition = CGPoint(
            x: bounds.midX,
            y: bounds.midY + (zoomedOut ? Self.zoomedOutVerticalOffset : 0)
        )
        let targetTransform = CATransform3DMakeAffineTransform(CGAffineTransform(scaleX: scale, y: scale))
        let targetCornerRadius = zoomedOut ? 18 / scale : 0
        let targetBorderWidth = zoomedOut ? 1.25 / scale : 0

        guard animated, container.bounds.width > 0, container.bounds.height > 0 else {
            container.position = targetPosition
            container.transform = targetTransform
            container.cornerRadius = targetCornerRadius
            container.borderWidth = targetBorderWidth
            container.borderColor = UIColor.white.withAlphaComponent(0.52).cgColor
            return
        }

        let visible = container.presentation()
        let fromPosition = visible?.position ?? container.position
        let fromTransform = visible?.transform ?? container.transform
        let fromCornerRadius = visible?.cornerRadius ?? container.cornerRadius
        let fromBorderWidth = visible?.borderWidth ?? container.borderWidth
        container.removeAnimation(forKey: "screenZoom")

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        container.position = targetPosition
        container.transform = targetTransform
        container.cornerRadius = targetCornerRadius
        container.borderWidth = targetBorderWidth
        container.borderColor = UIColor.white.withAlphaComponent(0.52).cgColor
        CATransaction.commit()

        let position = CABasicAnimation(keyPath: "position")
        position.fromValue = NSValue(cgPoint: fromPosition)
        position.toValue = NSValue(cgPoint: targetPosition)
        let transform = CABasicAnimation(keyPath: "transform")
        transform.fromValue = NSValue(caTransform3D: fromTransform)
        transform.toValue = NSValue(caTransform3D: targetTransform)
        let cornerRadius = CABasicAnimation(keyPath: "cornerRadius")
        cornerRadius.fromValue = fromCornerRadius
        cornerRadius.toValue = targetCornerRadius
        let borderWidth = CABasicAnimation(keyPath: "borderWidth")
        borderWidth.fromValue = fromBorderWidth
        borderWidth.toValue = targetBorderWidth

        let animation = CAAnimationGroup()
        animation.animations = [position, transform, cornerRadius, borderWidth]
        animation.duration = Self.zoomDuration
        animation.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        container.add(animation, forKey: "screenZoom")
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        deliver(touches, action: 0, createsPointers: true, removesPointers: false)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        deliver(touches, action: 2, createsPointers: false, removesPointers: false)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        deliver(touches, action: 1, createsPointers: false, removesPointers: true)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        defer { pointerIds.removeAll() }
        guard let touch = touches.first(where: { pointerIds[ObjectIdentifier($0)] != nil }),
              let pointerId = pointerIds[ObjectIdentifier(touch)] else { return }
        onTouches?([IOSScreenTouchSample(
            action: 3,
            pointerId: pointerId,
            location: unscaledLocation(touch.location(in: self)),
            pressure: 0
        )], bounds.size)
    }

    private func deliver(
        _ touches: Set<UITouch>,
        action: UInt8,
        createsPointers: Bool,
        removesPointers: Bool
    ) {
        var samples: [IOSScreenTouchSample] = []
        for touch in touches {
            let key = ObjectIdentifier(touch)
            let pointerId: UInt64
            if let existing = pointerIds[key] {
                pointerId = existing
            } else if createsPointers {
                pointerId = nextPointerId
                nextPointerId &+= 1
                pointerIds[key] = pointerId
            } else {
                continue
            }
            samples.append(IOSScreenTouchSample(
                action: action,
                pointerId: pointerId,
                location: unscaledLocation(touch.location(in: self)),
                pressure: action == 1 ? 0 : normalizedPressure(touch)
            ))
            if removesPointers { pointerIds.removeValue(forKey: key) }
        }
        if !samples.isEmpty { onTouches?(samples, bounds.size) }
    }

    private func normalizedPressure(_ touch: UITouch) -> Double {
        guard touch.maximumPossibleForce > 0, touch.force > 0 else { return 1 }
        return min(1, max(0, Double(touch.force / touch.maximumPossibleForce)))
    }

    private func unscaledLocation(_ point: CGPoint) -> CGPoint {
        guard zoomedOut else { return point }
        let baseCenter = CGPoint(x: bounds.midX, y: bounds.midY)
        let visualCenter = CGPoint(
            x: baseCenter.x,
            y: baseCenter.y + Self.zoomedOutVerticalOffset
        )
        return CGPoint(
            x: baseCenter.x + (point.x - visualCenter.x) / Self.zoomedOutScale,
            y: baseCenter.y + (point.y - visualCenter.y) / Self.zoomedOutScale
        )
    }
}

private nonisolated struct IOSVideoDataReader {
    private let data: Data
    private var offset = 0
    init(_ data: Data) { self.data = data }

    mutating func uint32() throws -> UInt32 {
        guard offset + 4 <= data.count else { throw IOSScreenTransportError.channelClosed }
        defer { offset += 4 }
        return data[offset..<(offset + 4)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
}

private nonisolated func h264ParameterSets(_ data: Data) -> [Data] {
    if data.count >= 7, data.first == 1 {
        var offset = 5
        let spsCount = Int(data[offset] & 0x1f)
        offset += 1
        var sets: [Data] = []
        for _ in 0..<spsCount {
            guard offset + 2 <= data.count else { return [] }
            let size = Int(data[offset]) << 8 | Int(data[offset + 1]); offset += 2
            guard offset + size <= data.count else { return [] }
            sets.append(data.subdata(in: offset..<(offset + size))); offset += size
        }
        guard offset < data.count else { return sets }
        let ppsCount = Int(data[offset]); offset += 1
        for _ in 0..<ppsCount {
            guard offset + 2 <= data.count else { return [] }
            let size = Int(data[offset]) << 8 | Int(data[offset + 1]); offset += 2
            guard offset + size <= data.count else { return [] }
            sets.append(data.subdata(in: offset..<(offset + size))); offset += size
        }
        return sets
    }
    return annexBNALUnits(data)
}

private nonisolated func annexBNALUnits(_ data: Data) -> [Data] {
    let bytes = [UInt8](data)
    var starts: [(index: Int, prefix: Int)] = []
    var index = 0
    while index + 3 <= bytes.count {
        if index + 4 <= bytes.count, bytes[index] == 0, bytes[index + 1] == 0,
           bytes[index + 2] == 0, bytes[index + 3] == 1 {
            starts.append((index, 4)); index += 4
        } else if bytes[index] == 0, bytes[index + 1] == 0, bytes[index + 2] == 1 {
            starts.append((index, 3)); index += 3
        } else { index += 1 }
    }
    guard !starts.isEmpty else { return [data] }
    return starts.indices.compactMap { position in
        let start = starts[position].index + starts[position].prefix
        let end = position + 1 < starts.count ? starts[position + 1].index : bytes.count
        guard end > start else { return nil }
        return Data(bytes[start..<end])
    }
}

private nonisolated func annexBToAVCC(_ data: Data) -> Data {
    let units = annexBNALUnits(data)
    guard !(units.count == 1 && units[0] == data) else { return data }
    var result = Data()
    for unit in units {
        result.appendScreenUInt32(UInt32(unit.count))
        result.append(unit)
    }
    return result
}

private nonisolated extension Data {
    mutating func appendScreenUInt16(_ value: UInt16) {
        append(UInt8((value >> 8) & 0xff)); append(UInt8(value & 0xff))
    }
    mutating func appendScreenUInt32(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xff)); append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 8) & 0xff)); append(UInt8(value & 0xff))
    }
    mutating func appendScreenUInt64(_ value: UInt64) {
        for shift in stride(from: 56, through: 0, by: -8) {
            append(UInt8((value >> UInt64(shift)) & 0xff))
        }
    }
}
