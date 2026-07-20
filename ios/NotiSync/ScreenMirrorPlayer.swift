import AVFoundation
import Combine
import CoreMedia
import Foundation
import SwiftUI
import UIKit

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

    func sendTouch(
        action: UInt8,
        x: Int,
        y: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        pressure: Double
    ) async throws {
        guard sourceWidth > 0, sourceWidth <= Int(UInt16.max),
              sourceHeight > 0, sourceHeight <= Int(UInt16.max) else { return }
        let fixedPressure: UInt16
        if pressure <= 0 { fixedPressure = 0 }
        else if pressure >= 1 { fixedPressure = UInt16.max }
        else { fixedPressure = UInt16(min(Int(UInt16.max), Int(pressure * 65_536))) }
        var frame = Data([2, action])
        frame.appendScreenUInt64(0) // one touchscreen pointer
        frame.appendScreenUInt32(UInt32(max(0, min(sourceWidth - 1, x))))
        frame.appendScreenUInt32(UInt32(max(0, min(sourceHeight - 1, y))))
        frame.appendScreenUInt16(UInt16(sourceWidth))
        frame.appendScreenUInt16(UInt16(sourceHeight))
        frame.appendScreenUInt16(fixedPressure)
        frame.appendScreenUInt32(0) // action button
        frame.appendScreenUInt32(0) // buttons
        try await connection.send(frame)
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
    private let onDimensions: @Sendable (CGSize) -> Void
    private let onFailure: @Sendable (Error) -> Void
    private var task: Task<Void, Never>?
    private var formatDescription: CMVideoFormatDescription?

    init(
        connection: IOSScreenWireConnection,
        displayLayer: AVSampleBufferDisplayLayer,
        onDimensions: @escaping @Sendable (CGSize) -> Void,
        onFailure: @escaping @Sendable (Error) -> Void
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
            catch { self.onFailure(error) }
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
            throw IOSScreenTransportError.unsupportedStream("The Android source did not start an H.264 stream.")
        }
        let flags = try initial.uint32()
        guard flags & 0x80000000 != 0, flags & 0x7ffffffe == 0 else {
            throw IOSScreenTransportError.unsupportedStream("The Android source sent invalid stream metadata.")
        }
        try updateDimensions(width: Int(initial.uint32()), height: Int(initial.uint32()))

        while !Task.isCancelled {
            let header = try await connection.readExactly(12)
            var reader = IOSVideoDataReader(header)
            let firstWord = try reader.uint32()
            if firstWord & 0x80000000 != 0 {
                guard firstWord & 0x7ffffffe == 0 else {
                    throw IOSScreenTransportError.unsupportedStream("The Android source sent invalid resize metadata.")
                }
                try updateDimensions(width: Int(reader.uint32()), height: Int(reader.uint32()))
                formatDescription = nil
                await displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true)
                continue
            }
            let secondWord = try reader.uint32()
            let ptsAndFlags = (UInt64(firstWord) << 32) | UInt64(secondWord)
            let packetSize = Int(try reader.uint32())
            guard packetSize > 0, packetSize <= 16 * 1024 * 1024 else {
                throw IOSScreenTransportError.unsupportedStream("The Android source sent an oversized video packet.")
            }
            let packet = try await connection.readExactly(packetSize)
            let configFlag = UInt64(1) << 62
            if ptsAndFlags & configFlag != 0 {
                guard ptsAndFlags == configFlag else {
                    throw IOSScreenTransportError.unsupportedStream("The Android source sent invalid codec metadata.")
                }
                formatDescription = try makeH264FormatDescription(config: packet)
            } else if let formatDescription {
                let ptsMask = (UInt64(1) << 61) - 1
                try enqueue(packet: packet, ptsUs: Int64(ptsAndFlags & ptsMask), format: formatDescription)
            }
        }
    }

    private func updateDimensions(width: Int, height: Int) throws {
        guard width > 0, width <= 8_192, height > 0, height <= 8_192,
              Int64(width) * Int64(height) <= 16_000_000 else {
            throw IOSScreenTransportError.unsupportedStream("The Android source sent invalid video dimensions.")
        }
        onDimensions(CGSize(width: width, height: height))
    }

    private func makeH264FormatDescription(config: Data) throws -> CMVideoFormatDescription {
        let parameterSets = h264ParameterSets(config)
        guard let sps = parameterSets.first(where: { $0.first.map { $0 & 0x1f == 7 } == true }),
              let pps = parameterSets.first(where: { $0.first.map { $0 & 0x1f == 8 } == true }) else {
            throw IOSScreenTransportError.unsupportedStream("The Android H.264 encoder sent no SPS/PPS.")
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
        if renderer.status == .failed { renderer.flush() }
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
    @Published var inputText = ""

    let displayLayer = AVSampleBufferDisplayLayer()
    private var session: IOSScreenMirrorSession?
    private var decoder: IOSScreenVideoDecoder?
    private var control: IOSScreenControlWriter?
    private var lastTouch: CGPoint?
    private var touchActive = false
    private var stopped = false

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
    }

    func start(runtime: NotiSyncRuntime, sourceId: String, sourceName: String) async {
        stopped = false
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
            status = "Connected over local Wi-Fi"
            let decoder = IOSScreenVideoDecoder(
                connection: session.channels.video,
                displayLayer: displayLayer,
                onDimensions: { [weak self] size in
                    Task { @MainActor in self?.dimensions = size }
                },
                onFailure: { [weak self] error in
                    Task { @MainActor in
                        guard let self, !self.stopped else { return }
                        self.errorMessage = error.localizedDescription
                        self.connected = false
                    }
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

    func stop(runtime: NotiSyncRuntime) async {
        guard !stopped else { return }
        stopped = true
        decoder?.cancel()
        decoder = nil
        session?.cancelTransport()
        await runtime.endScreenMirror(sessionId: session?.sessionId)
        session = nil
        control = nil
        connected = false
    }

    func sendKey(_ keyCode: Int32) {
        guard let control else { return }
        Task { try? await control.sendKeyPress(keyCode) }
    }

    func togglePower() {
        guard let control else { return }
        Task { try? await control.togglePower() }
    }

    func sendInputText() {
        let text = inputText
        inputText = ""
        showsTextInput = false
        guard let control, !text.isEmpty, Data(text.utf8).count <= 300 else { return }
        Task { try? await control.sendText(text) }
    }

    func touchChanged(at point: CGPoint, in viewSize: CGSize) {
        guard let mapped = mappedTouch(point, viewSize: viewSize), let control else { return }
        lastTouch = mapped
        let action: UInt8 = touchActive ? 2 : 0
        touchActive = true
        Task {
            try? await control.sendTouch(
                action: action,
                x: Int(mapped.x), y: Int(mapped.y),
                sourceWidth: Int(dimensions.width), sourceHeight: Int(dimensions.height),
                pressure: 1
            )
        }
    }

    func touchEnded() {
        guard touchActive, let point = lastTouch, let control else { return }
        touchActive = false
        lastTouch = nil
        Task {
            try? await control.sendTouch(
                action: 1,
                x: Int(point.x), y: Int(point.y),
                sourceWidth: Int(dimensions.width), sourceHeight: Int(dimensions.height),
                pressure: 0
            )
        }
    }

    private func mappedTouch(_ point: CGPoint, viewSize: CGSize) -> CGPoint? {
        guard dimensions.width > 0, dimensions.height > 0,
              viewSize.width > 0, viewSize.height > 0 else { return nil }
        let scale = min(viewSize.width / dimensions.width, viewSize.height / dimensions.height)
        let rendered = CGSize(width: dimensions.width * scale, height: dimensions.height * scale)
        let origin = CGPoint(x: (viewSize.width - rendered.width) / 2, y: (viewSize.height - rendered.height) / 2)
        let rect = CGRect(origin: origin, size: rendered)
        guard touchActive || rect.contains(point) else { return nil }
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
    @StateObject private var model = IOSScreenMirrorPlayerModel()
    let sourceId: String
    let sourceName: String

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScreenMirrorVideoSurface(layer: model.displayLayer)
                    .overlay {
                        GeometryReader { geometry in
                            Color.clear
                                .contentShape(Rectangle())
                                .gesture(
                                    DragGesture(minimumDistance: 0)
                                        .onChanged { model.touchChanged(at: $0.location, in: geometry.size) }
                                        .onEnded { _ in model.touchEnded() }
                                )
                        }
                    }
                if !model.connected {
                    VStack(spacing: 14) {
                        if let error = model.errorMessage {
                            Image(systemName: "wifi.exclamationmark")
                                .font(.largeTitle)
                            Text(error).multilineTextAlignment(.center)
                            Button("Close") { dismiss() }.buttonStyle(.borderedProminent)
                        } else {
                            ProgressView().tint(.white)
                            Text(runtime.screenMirrorPhase ?? model.status)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .foregroundStyle(.white)
                    .padding(28)
                    .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 20))
                    .padding()
                }
            }
            .navigationTitle(sourceName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.black.opacity(0.82), for: .navigationBar, .bottomBar)
            .toolbarColorScheme(.dark, for: .navigationBar, .bottomBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button { model.sendKey(4) } label: { Label("Back", systemImage: "chevron.backward") }
                    Spacer()
                    Button { model.sendKey(3) } label: { Label("Home", systemImage: "circle") }
                    Spacer()
                    Button { model.sendKey(187) } label: { Label("Recent Apps", systemImage: "square.on.square") }
                    Spacer()
                    Button { model.showsTextInput = true } label: { Label("Type", systemImage: "keyboard") }
                    Spacer()
                    Button { model.togglePower() } label: { Label("Power", systemImage: "power") }
                }
            }
        }
        .task { await model.start(runtime: runtime, sourceId: sourceId, sourceName: sourceName) }
        .onDisappear { Task { await model.stop(runtime: runtime) } }
        .alert("Type on Android", isPresented: $model.showsTextInput) {
            TextField("Text", text: $model.inputText)
            Button("Cancel", role: .cancel) { model.inputText = "" }
            Button("Send") { model.sendInputText() }
        } message: {
            Text("Sends up to 300 UTF-8 bytes to the focused Android field.")
        }
    }
}

private struct ScreenMirrorVideoSurface: UIViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeUIView(context: Context) -> UIView {
        let view = ScreenMirrorLayerHostView()
        view.backgroundColor = .black
        view.hostedLayer = layer
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        (uiView as? ScreenMirrorLayerHostView)?.hostedLayer = layer
    }
}

private final class ScreenMirrorLayerHostView: UIView {
    var hostedLayer: AVSampleBufferDisplayLayer? {
        didSet {
            if oldValue !== hostedLayer { oldValue?.removeFromSuperlayer() }
            if let hostedLayer, hostedLayer.superlayer !== layer { layer.addSublayer(hostedLayer) }
            setNeedsLayout()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        hostedLayer?.frame = bounds
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
