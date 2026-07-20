import AVFoundation
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
        CMSetAttachment(
            sample,
            key: kCMSampleAttachmentKey_DisplayImmediately,
            value: kCFBooleanTrue,
            attachmentMode: kCMAttachmentMode_ShouldNotPropagate
        )
        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding { renderer.flush() }
        renderer.enqueue(sample)
    }
}

@MainActor
final class IOSScreenMirrorPlayerModel: ObservableObject {
    @Published var dimensions = CGSize.zero
    @Published var status = "Preparing secure LAN session…"
    @Published var errorMessage: String?
    @Published var connected = false
    @Published var showsTextInput = false
    @Published var obscuresInputText = true
    @Published var inputText = ""
    @Published var zoomedOut = false

    let displayLayer = AVSampleBufferDisplayLayer()
    private var session: IOSScreenMirrorSession?
    private var decoder: IOSScreenVideoDecoder?
    private var control: IOSScreenControlWriter?
    private var activeTouchIds: Set<UInt64> = []
    private var lastTouchLocations: [UInt64: CGPoint] = [:]
    private var touchSendTask: Task<Void, Never>?
    private var videoVisibilityTask: Task<Void, Never>?
    private var playbackSuspended = false
    private var stopped = false

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
    }

    func start(runtime: NotiSyncRuntime, sourceId: String, sourceName: String) async {
        stopped = false
        connected = false
        errorMessage = nil
        dimensions = .zero
        status = "Preparing secure LAN session…"
        do {
            let session = try await runtime.openScreenMirror(sourceId: sourceId, sourceName: sourceName)
            guard !stopped else {
                session.cancelTransport()
                await runtime.endScreenMirror(sessionId: session.sessionId)
                return
            }
            self.session = session
            control = IOSScreenControlWriter(connection: session.channels.control)
            connected = true
            if playbackSuspended { queueVideoVisibility(false) }
            status = "Connected over local network"
            let decoder = IOSScreenVideoDecoder(
                connection: session.channels.video,
                displayLayer: displayLayer,
                onDimensions: { [weak self] size in
                    self?.dimensions = size
                },
                onFailure: { [weak self] error in
                    guard let self, !self.stopped else { return }
                    self.errorMessage = error.localizedDescription
                    self.connected = false
                }
            )
            self.decoder = decoder
            decoder.start()
        } catch {
            guard !stopped else { return }
            errorMessage = error.localizedDescription
            status = "Could not connect"
        }
    }

    func retry(runtime: NotiSyncRuntime, sourceId: String, sourceName: String) async {
        await stop(runtime: runtime)
        await start(runtime: runtime, sourceId: sourceId, sourceName: sourceName)
    }

    func stop(runtime: NotiSyncRuntime) async {
        guard !stopped else { return }
        stopped = true
        decoder?.cancel()
        decoder = nil
        touchSendTask?.cancel()
        touchSendTask = nil
        videoVisibilityTask?.cancel()
        videoVisibilityTask = nil
        session?.cancelTransport()
        await runtime.endScreenMirror(sessionId: session?.sessionId)
        session = nil
        control = nil
        activeTouchIds.removeAll()
        lastTouchLocations.removeAll()
        connected = false
    }

    func updatePlaybackActivity(isActive: Bool) {
        if isActive {
            guard playbackSuspended else { return }
            playbackSuspended = false
            guard connected else { return }
            // A suspended AVSampleBufferDisplayLayer may explicitly require a flush. Always flush here,
            // then make the source emit a fresh session boundary/config/IDR instead of resuming interframes.
            displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true)
            queueVideoRestart()
        } else {
            guard !playbackSuspended else { return }
            playbackSuspended = true
            activeTouchIds.removeAll()
            lastTouchLocations.removeAll()
            guard connected else { return }
            queueVideoVisibility(false)
        }
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
        obscuresInputText = true
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
        obscuresInputText = true
        showsTextInput = true
    }

    func cancelTextInput() {
        inputText = ""
        obscuresInputText = true
        showsTextInput = false
    }

    func toggleDisplayZoom() {
        zoomedOut.toggle()
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
        .onAppear { model.updatePlaybackActivity(isActive: scenePhase == .active) }
        .onChange(of: scenePhase) { _, phase in
            model.updatePlaybackActivity(isActive: phase == .active)
        }
        .task { await model.start(runtime: runtime, sourceId: sourceId, sourceName: sourceName) }
        .onDisappear { Task { await model.stop(runtime: runtime) } }
        .sheet(isPresented: $model.showsTextInput) {
            NavigationStack {
                Form {
                    Section {
                        Group {
                            if model.obscuresInputText {
                                SecureField("Text", text: $model.inputText)
                                    .textContentType(.password)
                            } else {
                                TextField("Text", text: $model.inputText)
                            }
                        }
                        .focused($inputIsFocused)
                        .submitLabel(.send)
                        .onSubmit {
                            guard !model.inputText.isEmpty,
                                  Data(model.inputText.utf8).count <= 300 else { return }
                            model.sendInputText()
                        }

                        Toggle("Hide text", isOn: $model.obscuresInputText)
                    } footer: {
                        Text("Sends up to 300 UTF-8 bytes to the focused field.")
                    }
                }
                .navigationTitle("Type on Screen")
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
            .onChange(of: model.obscuresInputText) { _, _ in inputIsFocused = true }
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

            HStack {
                viewerControl("Close screen viewer", systemImage: "xmark") { dismiss() }
                Spacer()
                if model.connected {
                    viewerControl(
                        model.zoomedOut ? "Fill Screen" : "Zoom Out",
                        systemImage: model.zoomedOut
                            ? "arrow.up.left.and.arrow.down.right"
                            : "minus.magnifyingglass"
                    ) { model.toggleDisplayZoom() }
                }
            }
        }
    }

    @ViewBuilder
    private var viewerTitle: some View {
        if #available(iOS 26.0, *) {
            Text(sourceName)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(maxWidth: 240)
                .frame(height: 40)
                .glassEffect(.regular, in: .capsule)
        } else {
            Text(sourceName)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(maxWidth: 240)
                .frame(height: 40)
                .background(.black.opacity(0.62), in: Capsule())
                .overlay(Capsule().stroke(.white.opacity(0.16), lineWidth: 0.5))
        }
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
            viewerControl("Back", systemImage: "chevron.backward") { model.sendKey(4) }
            viewerControl("Home", systemImage: "circle") { model.sendKey(3) }
            viewerControl("Recent Apps", systemImage: "square.on.square") { model.sendKey(187) }
            viewerControl("Type", systemImage: "keyboard") { model.beginTextInput() }
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
        view.configure(sourceSize: sourceSize, zoomedOut: zoomedOut, animated: false)
        view.onTouches = onTouches
        return view
    }

    func updateUIView(_ uiView: ScreenMirrorLayerHostView, context: Context) {
        uiView.hostedLayer = layer
        uiView.configure(sourceSize: sourceSize, zoomedOut: zoomedOut, animated: true)
        uiView.onTouches = onTouches
    }
}

private final class ScreenMirrorLayerHostView: UIView {
    private static let zoomedOutScale: CGFloat = 0.76
    private static let zoomDuration: CFTimeInterval = 0.32

    var onTouches: (([IOSScreenTouchSample], CGSize) -> Void)?
    private var pointerIds: [ObjectIdentifier: UInt64] = [:]
    private var nextPointerId: UInt64 = 0
    private var sourceSize = CGSize.zero
    private var zoomedOut = false

    var hostedLayer: AVSampleBufferDisplayLayer? {
        didSet {
            if oldValue !== hostedLayer { oldValue?.removeFromSuperlayer() }
            if let hostedLayer, hostedLayer.superlayer !== layer { layer.addSublayer(hostedLayer) }
            setNeedsLayout()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isUserInteractionEnabled = true
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isMultipleTouchEnabled = true
        isUserInteractionEnabled = true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let hostedLayer else { return }
        let size = aspectFitSourceSize()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        hostedLayer.bounds = CGRect(origin: .zero, size: size)
        hostedLayer.position = CGPoint(x: bounds.midX, y: bounds.midY)
        applyZoomState(to: hostedLayer)
        CATransaction.commit()
    }

    func configure(sourceSize: CGSize, zoomedOut: Bool, animated: Bool) {
        let sourceChanged = self.sourceSize != sourceSize
        if sourceChanged {
            self.sourceSize = sourceSize
            setNeedsLayout()
            layoutIfNeeded()
        }
        guard self.zoomedOut != zoomedOut else { return }
        self.zoomedOut = zoomedOut
        guard let hostedLayer else { return }
        CATransaction.begin()
        CATransaction.setAnimationDuration(animated ? Self.zoomDuration : 0)
        CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeInEaseOut))
        applyZoomState(to: hostedLayer)
        CATransaction.commit()
    }

    private func aspectFitSourceSize() -> CGSize {
        guard bounds.width > 0, bounds.height > 0 else { return .zero }
        guard sourceSize.width > 0, sourceSize.height > 0 else { return bounds.size }
        let scale = min(bounds.width / sourceSize.width, bounds.height / sourceSize.height)
        return CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale)
    }

    private func applyZoomState(to hostedLayer: AVSampleBufferDisplayLayer) {
        let scale = zoomedOut ? Self.zoomedOutScale : 1
        hostedLayer.setAffineTransform(CGAffineTransform(scaleX: scale, y: scale))
        hostedLayer.cornerRadius = zoomedOut ? 18 / scale : 0
        hostedLayer.borderWidth = zoomedOut ? 1.25 / scale : 0
        hostedLayer.borderColor = UIColor.white.withAlphaComponent(0.52).cgColor
        hostedLayer.masksToBounds = zoomedOut
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
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        return CGPoint(
            x: center.x + (point.x - center.x) / Self.zoomedOutScale,
            y: center.y + (point.y - center.y) / Self.zoomedOutScale
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
