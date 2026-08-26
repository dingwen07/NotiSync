import SwiftUI
import UniformTypeIdentifiers
import UIKit

enum SshManagedGenerationAlgorithm: String, CaseIterable, Identifiable {
    case p256
    case ed25519
    case rsa

    var id: String { rawValue }
    var title: String {
        switch self {
        case .p256: "ECDSA P-256"
        case .ed25519: "Ed25519"
        case .rsa: "RSA"
        }
    }
}

enum SshApprovalRememberChoice: String, CaseIterable, Identifiable {
    case none
    case peer
    case peerAndHost

    var id: String { rawValue }
}

struct SshKeyProviderView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.scenePhase) private var scenePhase
    @State private var state = SshKeyProviderStore.snapshot()
    @State private var showingFileImporter = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                if !pending.isEmpty {
                    Section("Needs Review") {
                        ForEach(pending) { request in requestRow(request) }
                    }
                }

                Section {
                    if state.keys.isEmpty {
                        ContentUnavailableView(
                            "No SSH Keys",
                            systemImage: "key.horizontal",
                            description: Text("Generate, import, or recover a passkey-backed SSH key.")
                        )
                        .listRowBackground(Color.clear)
                    } else {
                        ForEach(state.keys) { key in
                            Button {
                                runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(kind: .keyDetails(key.id))
                            } label: {
                                SshKeyRow(key: key)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: {
                    Text("Keys")
                } footer: {
                    Text("Managed private keys stay in this device’s Keychain. Passkey private keys remain with your selected credential provider.")
                }

                Section {
                    if knownHosts.isEmpty {
                        ContentUnavailableView(
                            "No Known Hosts",
                            systemImage: "server.rack",
                            description: Text("Verified host-key fingerprints appear after you manually approve an SSH signature.")
                        )
                        .listRowBackground(Color.clear)
                    } else {
                        ForEach(knownHosts) { host in
                            Button {
                                runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(
                                    kind: .knownHost(host.hostKeyBlobSha256)
                                )
                            } label: {
                                SshKnownHostRow(host: host)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: {
                    Text("Known Hosts")
                } footer: {
                    Text("Hostnames are display labels only. SSH trust is bound to the verified host-key fingerprint.")
                }

                if !history.isEmpty {
                    Section("Signing and Import History") {
                        ForEach(history) { request in requestRow(request) }
                    }
                }
            }
            .navigationTitle("SSH Key Provider")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(kind: .generateManagedKey)
                        } label: {
                            Label("Generate Managed Key", systemImage: "plus.circle")
                        }
                        Button {
                            runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(kind: .createPasskey)
                        } label: {
                            Label("Create Passkey Key", systemImage: "person.badge.key")
                        }
                        Button {
                            runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(kind: .recoverPasskey(nil))
                        } label: {
                            Label("Use Existing Passkey", systemImage: "arrow.clockwise.icloud")
                        }
                        Divider()
                        Button {
                            showingFileImporter = true
                        } label: {
                            Label("Import from File", systemImage: "doc.badge.plus")
                        }
                        Button {
                            importClipboard()
                        } label: {
                            Label("Import from Clipboard", systemImage: "doc.on.clipboard")
                        }
                    } label: {
                        Label("Add SSH Key", systemImage: "plus")
                    }
                }
            }
            .refreshable {
                SshKeyProviderStore.expireDueRequests()
                refresh()
            }
            .onAppear(perform: refresh)
            .onChange(of: runtime.sshKeyProviderRevision) { _, _ in refresh() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { refresh() }
            }
            .fileImporter(
                isPresented: $showingFileImporter,
                allowedContentTypes: [.data, .plainText, .item],
                allowsMultipleSelection: false,
                onCompletion: importDocument
            )
            .alert("SSH Key Import", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var pending: [SshProviderRequestRecord] {
        state.requests.filter { $0.status == .pendingReview || $0.status == .responsePendingSend }
            .sorted { $0.requestedAt < $1.requestedAt }
    }

    private var history: [SshProviderRequestRecord] {
        state.requests.filter(\.status.isTerminal).sorted {
            ($0.completedAt ?? $0.requestedAt) > ($1.completedAt ?? $1.requestedAt)
        }
    }

    private var knownHosts: [SshKnownHostRecord] { SshKeyProviderStore.knownHosts() }

    @ViewBuilder
    private func requestRow(_ request: SshProviderRequestRecord) -> some View {
        Button {
            runtime.presentSshKeyProviderRequest(request.id)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: request.kind == .sign ? "signature" : "square.and.arrow.down")
                    .font(.title3)
                    .frame(width: 28)
                    .foregroundStyle(request.status == .pendingReview ? Color.orange : Color.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(request.kind == .sign ? "SSH signing request" : "SSH key import")
                        .foregroundStyle(.primary)
                    Text(request.requesterDisplayName ?? shortId(request.requesterClientId))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(statusTitle(request))
                    .font(.caption)
                    .foregroundStyle(request.status == .pendingReview ? Color.orange : Color.secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private func refresh() {
        state = SshKeyProviderStore.snapshot()
    }

    private func importClipboard() {
        guard let text = UIPasteboard.general.string, !text.isEmpty else {
            errorMessage = String(
                localized: "The clipboard does not contain a private key.",
                comment: "Validation error shown when an SSH private key cannot be read from the clipboard."
            )
            return
        }
        let data = Data(text.utf8)
        guard data.count <= 256 * 1024 else {
            errorMessage = String(
                localized: "The private-key file is larger than 256 KiB.",
                comment: "Validation error shown when an imported SSH private-key file exceeds the size limit."
            )
            return
        }
        runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(kind: .importManagedKey(data, suggestedName: nil))
    }

    private func importDocument(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile != false, (values.fileSize ?? 0) <= 256 * 1024 else {
                throw SshUIError.message(String(
                    localized: "The private-key file must be a regular file no larger than 256 KiB.",
                    comment: "Validation error shown when an imported SSH private-key URL is not a suitably sized regular file."
                ))
            }
            let data = try readBoundedPrivateKey(from: url)
            guard !data.isEmpty else {
                throw SshUIError.message(String(
                    localized: "The private-key file is empty or larger than 256 KiB.",
                    comment: "Validation error shown when an imported SSH private-key file is empty or exceeds the size limit."
                ))
            }
            runtime.sshKeyProviderSheetDestination = SshKeyProviderSheetDestination(
                kind: .importManagedKey(data, suggestedName: url.deletingPathExtension().lastPathComponent)
            )
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func statusTitle(_ request: SshProviderRequestRecord) -> String {
        switch request.status {
        case .pendingReview: String(localized: "Pending")
        case .responsePendingSend: String(localized: "Sending")
        case .sent: request.outcome.map(outcomeTitle) ?? String(localized: "Completed")
        case .cancelled: String(localized: "Cancelled")
        case .expired: String(localized: "Expired")
        }
    }

    private func outcomeTitle(_ outcome: SshProviderRequestOutcome) -> String {
        switch outcome {
        case .signed: String(localized: "Signed")
        case .imported: String(localized: "Imported")
        case .alreadyPresent: String(localized: "Already added")
        case .rejected: String(localized: "Rejected")
        case .failed: String(localized: "Failed")
        case .cancelled: String(localized: "Cancelled")
        case .expired: String(localized: "Expired")
        }
    }

    private func readBoundedPrivateKey(from url: URL) throws -> Data {
        let maximumBytes = 256 * 1_024
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var data = Data()
        while data.count <= maximumBytes {
            let remainingWithSentinel = maximumBytes - data.count + 1
            guard let chunk = try handle.read(upToCount: min(64 * 1_024, remainingWithSentinel)),
                  !chunk.isEmpty else { return data }
            data.append(chunk)
            if data.count > maximumBytes {
                throw SshUIError.message(String(
                    localized: "The private-key file is empty or larger than 256 KiB.",
                    comment: "Validation error shown when an imported SSH private-key file is empty or exceeds the size limit."
                ))
            }
        }
        return data
    }
}

struct SshKeyProviderSheet: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    let destination: SshKeyProviderSheetDestination

    var body: some View {
        NavigationStack {
            switch destination.kind {
            case .request(let id):
                SshRequestDetailView(requestId: id)
            case .keyDetails(let id):
                SshKeyDetailView(keyId: id)
            case .knownHost(let digest):
                SshKnownHostDetailView(hostKeyBlobSha256: digest)
            case .generateManagedKey:
                SshGenerateKeyView()
            case .importManagedKey(let data, let suggestedName):
                SshLocalImportView(data: data, suggestedName: suggestedName)
            case .createPasskey:
                SshPasskeyView(mode: .create, initialRecoveryRecord: nil)
            case .recoverPasskey(let record):
                SshPasskeyView(mode: .recover, initialRecoveryRecord: record)
            }
        }
    }
}

private struct SshKeyRow: View {
    let key: SshProviderKeyRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: key.isWebAuthn ? "person.badge.key" : "key.horizontal")
                .font(.title3)
                .frame(width: 28)
                .foregroundStyle(key.isWebAuthn ? Color.blue : Color.accentColor)
            VStack(alignment: .leading, spacing: 3) {
                Text(key.displayName)
                Text("\(algorithmTitle(key.algorithm)) · \(sshFingerprint(key.publicKeyBlob))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
    }
}

private struct SshKnownHostRow: View {
    let host: SshKnownHostRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "server.rack")
                .font(.title3)
                .frame(width: 28)
                .foregroundStyle(Color.accentColor)
            VStack(alignment: .leading, spacing: 3) {
                Text(host.hostname ?? String(localized: "Unknown Host"))
                    .foregroundStyle(.primary)
                Text(sshFingerprintDigest(host.hostKeyBlobSha256))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
    }
}

private struct SshKnownHostDetailView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    let hostKeyBlobSha256: Data
    @State private var hostname = ""
    @State private var confirmingForget = false
    @State private var busy = false
    @State private var errorMessage: String?

    private var host: SshKnownHostRecord? {
        SshKeyProviderStore.knownHost(hostKeyBlobSha256: hostKeyBlobSha256)
    }

    var body: some View {
        Group {
            if let host {
                Form {
                    Section("Host Key") {
                        LabeledContent("Name", value: host.hostname ?? String(localized: "Unknown Host"))
                        LabeledContent("Fingerprint", value: sshFingerprintDigest(host.hostKeyBlobSha256))
                        LabeledContent("First Approved", value: dateText(host.firstApprovedAt))
                        LabeledContent("Last Approved", value: dateText(host.lastApprovedAt))
                    }
                    Section("Display Label") {
                        TextField("Hostname", text: $hostname)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Text("This is an unvalidated display label. It is never used to decide whether an SSH host key is trusted.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button("Save") { save() }
                            .disabled(busy || hostname.utf8.count > 1_024)
                    }
                    Section {
                        Button("Forget Host", role: .destructive) { confirmingForget = true }
                    }
                }
            } else {
                ContentUnavailableView("Known Host Not Found", systemImage: "server.rack")
            }
        }
        .navigationTitle("Known Host")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .onAppear { hostname = host?.hostname ?? "" }
        .onChange(of: runtime.sshKeyProviderRevision) { _, _ in
            if !busy { hostname = host?.hostname ?? "" }
        }
        .confirmationDialog("Forget this Known Host?", isPresented: $confirmingForget, titleVisibility: .visible) {
            Button("Forget Host", role: .destructive) { forget() }
        } message: {
            Text("This removes the display mapping only. Remembered SSH authorizations are not revoked.")
        }
        .alert("Known Host", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) { Button("OK", role: .cancel) {} } message: { Text(errorMessage ?? "") }
    }

    private func save() {
        busy = true
        Task {
            do {
                try await runtime.updateSshKnownHostHostname(
                    hostKeyBlobSha256: hostKeyBlobSha256,
                    hostname: hostname
                )
                busy = false
            } catch {
                busy = false
                errorMessage = error.localizedDescription
            }
        }
    }

    private func forget() {
        busy = true
        Task {
            do {
                try await runtime.forgetSshKnownHost(hostKeyBlobSha256: hostKeyBlobSha256)
                dismiss()
            } catch {
                busy = false
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct SshKeyDetailView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    let keyId: String
    @State private var confirmingDelete = false
    @State private var authorizationToForget: SshRememberedAuthorizationRecord?
    @State private var errorMessage: String?

    var body: some View {
        Group {
            if let key = SshKeyProviderStore.key(id: keyId) {
                Form {
                    Section {
                        LabeledContent("Name", value: key.displayName)
                        LabeledContent("Algorithm", value: algorithmTitle(key.algorithm))
                        LabeledContent("Fingerprint", value: sshFingerprint(key.publicKeyBlob))
                        LabeledContent(
                            "Protection",
                            value: key.isWebAuthn
                                ? String(localized: "Passkey provider")
                                : String(localized: "Device-only Keychain")
                        )
                        LabeledContent("Created", value: dateText(key.createdAt))
                        if let expiresAt = key.expiresAt {
                            LabeledContent("Expires", value: dateText(expiresAt))
                        }
                    }
                    Section("Public Key") {
                        ShareLink(item: sshPublicKeyText(key)) {
                            Label("Share Public Key", systemImage: "square.and.arrow.up")
                        }
                        Button {
                            UIPasteboard.general.string = sshPublicKeyText(key)
                        } label: {
                            Label("Copy Public Key", systemImage: "doc.on.doc")
                        }
                    }
                    if key.isWebAuthn {
                        Section("Passkey") {
                            if let relyingPartyId = key.relyingPartyId {
                                LabeledContent("Relying Party", value: relyingPartyId)
                            }
                            LabeledContent(
                                "Backup Eligible",
                                value: key.backupEligible == true ? String(localized: "Yes") : String(localized: "No")
                            )
                            LabeledContent("Credential State", value: passkeyBackupTitle(key))
                            if key.backupEligible != true {
                                Text("This passkey is not eligible for credential-provider backup. Keep the public recovery record, but signing still requires this authenticator.")
                                    .font(.footnote)
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                    if let recovery = key.recoveryRecordJSON {
                        Section("Passkey Recovery Record") {
                            LabeledContent(
                                "Passwords",
                                value: key.recoveryRecordSaved == true
                                    ? String(localized: "Saved") : String(localized: "Not saved")
                            )
                            if key.recoveryRecordSaved != true {
                                Button {
                                    Task {
                                        do {
                                            try await runtime.saveSshPasskeyRecoveryRecord(id: key.id)
                                        } catch {
                                            errorMessage = error.localizedDescription
                                        }
                                    }
                                } label: {
                                    Label("Save Recovery Record to Passwords", systemImage: "key.viewfinder")
                                }
                            }
                            ShareLink(item: recovery) {
                                Label("Share Public Recovery Record", systemImage: "square.and.arrow.up")
                            }
                            Text("This record contains public metadata only. The matching passkey is still required to sign.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Section("Remembered Authorizations") {
                        if key.isWebAuthn {
                            Text("Passkey-backed keys always require credential-provider verification and cannot remember approvals.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else if rememberedAuthorizations.isEmpty {
                            Text("No remembered authorizations for this key.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(rememberedAuthorizations) { authorization in
                                HStack(alignment: .top, spacing: 12) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(requesterName(for: authorization))
                                        Text(authorizationScope(authorization))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        Text(dateText(authorization.createdAt))
                                            .font(.caption2)
                                            .foregroundStyle(.tertiary)
                                    }
                                    Spacer()
                                    Button(role: .destructive) {
                                        authorizationToForget = authorization
                                    } label: {
                                        Image(systemName: "trash")
                                    }
                                    .accessibilityLabel("Forget Authorization")
                                }
                            }
                        }
                    }
                    Section {
                        Button("Delete Key", role: .destructive) { confirmingDelete = true }
                    }
                }
            } else {
                ContentUnavailableView("Key Not Found", systemImage: "key.slash")
            }
        }
        .navigationTitle("SSH Key")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .confirmationDialog("Delete this SSH key?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete Key", role: .destructive) {
                Task {
                    do {
                        try await runtime.deleteSshKey(id: keyId)
                        dismiss()
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        } message: {
            if SshKeyProviderStore.key(id: keyId)?.isWebAuthn == true {
                Text("This removes the SSH key and its public recovery metadata from NotiSync. The passkey remains with your credential provider.")
            } else {
                Text("The private key and remembered authorizations will be removed from this device.")
            }
        }
        .confirmationDialog(
            "Forget remembered authorization?",
            isPresented: Binding(
                get: { authorizationToForget != nil },
                set: { if !$0 { authorizationToForget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Forget Authorization", role: .destructive) {
                guard let authorization = authorizationToForget else { return }
                authorizationToForget = nil
                Task {
                    do {
                        try await runtime.forgetSshRememberedAuthorization(
                            id: authorization.id,
                            providerKeyId: keyId
                        )
                    } catch {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        } message: {
            Text("Future requests from this device will require approval when they no longer match another remembered authorization.")
        }
        .alert("Could Not Delete Key", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) { Button("OK", role: .cancel) {} } message: { Text(errorMessage ?? "") }
    }

    private var rememberedAuthorizations: [SshRememberedAuthorizationRecord] {
        SshKeyProviderStore.snapshot().rememberedAuthorizations
            .filter { $0.providerKeyId == keyId && SshKeyProviderStore.validRememberedAuthorizationRecord($0, providerKeyId: keyId) }
            .sorted { $0.createdAt > $1.createdAt }
    }

    private func requesterName(for authorization: SshRememberedAuthorizationRecord) -> String {
        SshKeyProviderStore.snapshot().requests.reversed().first {
            $0.requesterClientId == authorization.requesterClientId &&
                $0.requesterDisplayName?.isEmpty == false
        }?.requesterDisplayName ?? shortId(authorization.requesterClientId)
    }

    private func authorizationScope(_ authorization: SshRememberedAuthorizationRecord) -> String {
        guard authorization.scope == SshRememberScope.PEER_HOST_KEY.rawValue,
              let digest = authorization.hostKeyBlobSha256 else {
            return String(localized: "All hosts")
        }
        let host = SshKeyProviderStore.knownHost(hostKeyBlobSha256: digest)
        return host?.hostname ?? sshFingerprintDigest(digest)
    }
}

private struct SshRequestDetailView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    let requestId: String
    @State private var rememberChoice: SshApprovalRememberChoice = .none
    @State private var importName = ""
    @State private var importPassphrase = ""
    @State private var importPreview: SshPrivateKeyImportPreview?
    @State private var previewBusy = false
    @State private var busy = false
    @State private var errorMessage: String?

    private var request: SshProviderRequestRecord? { SshKeyProviderStore.request(id: requestId) }

    var body: some View {
        Group {
            if let request {
                Form {
                    statusSection(request)
                    requestContextSection(request)
                    if request.kind == .sign { signContextSection(request) } else { importContextSection(request) }
                    if request.status == .pendingReview, request.kind == .sign, !request.confirmationRequired,
                       SshKeyProviderStore.key(id: request.providerKeyId ?? "")?.approvalPolicy == "ALLOW_REMEMBER" {
                        rememberSection(request)
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    if request.status == .pendingReview { approvalFooter(request) }
                }
            } else {
                ContentUnavailableView("Request Not Found", systemImage: "questionmark.folder")
            }
        }
        .navigationTitle(request?.kind == .sign ? "SSH Signing" : "SSH Key Import")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if request?.status != .pendingReview { Button("Close") { dismiss() } }
            }
        }
        .onAppear {
            if let name = request?.importSuggestedName { importName = name }
        }
        .onChange(of: runtime.sshKeyProviderRevision) { _, _ in
            if request?.status.isTerminal == true {
                busy = false
                previewBusy = false
                importPreview = nil
                importPassphrase.removeAll(keepingCapacity: false)
            }
        }
        .onChange(of: importPassphrase) { _, _ in
            importPreview = nil
        }
        .alert("SSH Request", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) { Button("OK", role: .cancel) {} } message: { Text(errorMessage ?? "") }
    }

    @ViewBuilder
    private func statusSection(_ request: SshProviderRequestRecord) -> some View {
        Section {
            VStack(spacing: 10) {
                Image(systemName: statusSymbol(request))
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(statusColor(request))
                Text(statusHeading(request))
                    .font(.headline)
                if request.status == .pendingReview {
                    Text("Approve only if you recognize the requesting device and destination.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else if let message = request.outcomeMessage {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }

    @ViewBuilder
    private func requestContextSection(_ request: SshProviderRequestRecord) -> some View {
        Section("Request") {
            LabeledContent("Device", value: request.requesterDisplayName ?? shortId(request.requesterClientId))
            auditValue("Device ID", request.requesterClientId)
            auditValue("Request ID", request.id)
            auditValue("Request Digest", sshFingerprintDigest(request.requestDigest))
            LabeledContent("Requested", value: dateText(request.requestedAt))
            LabeledContent("Expires", value: dateText(request.expiresAt))
            if let connectionId = request.connectionId { auditValue("Connection ID", connectionId) }
            if let key = request.providerKeyId.flatMap({ SshKeyProviderStore.key(id: $0) }) {
                LabeledContent("Key", value: key.displayName)
                LabeledContent("Fingerprint", value: sshFingerprint(key.publicKeyBlob))
            } else if let publicKeyBlob = request.publicKeyBlob {
                LabeledContent("Fingerprint", value: sshFingerprint(publicKeyBlob))
            }
            if request.outcome == .signed {
                LabeledContent("Approval", value: approvalMethodTitle(request.approvalDisposition))
            }
        }
    }

    @ViewBuilder
    private func signContextSection(_ request: SshProviderRequestRecord) -> some View {
        Section("SSH Signature") {
            if let algorithm = request.requestedSignatureAlgorithm {
                LabeledContent("Signature Algorithm", value: signatureAlgorithmTitle(algorithm))
            }
            if let flags = request.flags { LabeledContent("Agent Flags", value: String(flags)) }
            LabeledContent(
                "Confirmation",
                value: request.confirmationRequired
                    ? String(localized: "Required every time") : String(localized: "Remembering allowed")
            )
            if request.confirmationRequired {
                Text("The requester requires confirmation for every use, so this request cannot create or use a remembered authorization.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        if let destination = request.destination {
            Section("SSH Destination") {
                let knownName = destination.serverHostKeyBlobSha256.flatMap {
                    SshKeyProviderStore.knownHost(hostKeyBlobSha256: $0)?.hostname
                }
                LabeledContent(
                    "Host",
                    value: knownName ?? destination.hostAliases.first ?? String(localized: "Unknown")
                )
                if destination.hostAliases.count > 1 {
                    auditValue("Reported Aliases", destination.hostAliases.joined(separator: ", "))
                }
                if let username = destination.username { LabeledContent("Username", value: username) }
                LabeledContent("Direction", value: connectionDirectionTitle(destination.connectionDirection))
                if let service = destination.service { LabeledContent("Service", value: service) }
                if let method = destination.authenticationMethod {
                    LabeledContent("Authentication", value: method)
                }
                LabeledContent("Evidence", value: destinationProvenanceTitle(destination.provenance))
                if let digest = destination.serverHostKeyBlobSha256 {
                    LabeledContent("Host Key", value: sshFingerprintDigest(digest))
                }
            }
        }
        if request.processSource != nil || !request.processLineage.isEmpty {
            Section("Requester-Reported Process") {
                if let source = request.processSource {
                    LabeledContent("Source", value: processSourceTitle(source))
                }
                ForEach(Array(request.processLineage.enumerated()), id: \.offset) { _, process in
                    VStack(alignment: .leading) {
                        Text(process.displayName ?? process.executablePath ?? String(
                            localized: "Process \(process.pid)",
                            comment: "Fallback display name for a requester-reported process. The value is its process identifier."
                        ))
                        if let path = process.executablePath {
                            Text(path).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                        }
                    }
                }
                Text("Process information is context only and is not a security boundary.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func auditValue(_ label: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value)
                .font(.system(.footnote, design: .monospaced))
                .textSelection(.enabled)
        }
    }

    @ViewBuilder
    private func importContextSection(_ request: SshProviderRequestRecord) -> some View {
        Section("Import") {
            LabeledContent(
                "Source",
                value: request.importSourceType == "AGENT_IDENTITY"
                    ? String(localized: "Desktop ssh-add")
                    : String(localized: "NotiSync key file")
            )
            if request.status == .pendingReview {
                TextField("Key Name", text: $importName)
                if request.importSourceType == "PRIVATE_KEY_FILE" {
                    SecureField("Passphrase, if encrypted", text: $importPassphrase)
                }
                if let preview = importPreview {
                    importPreviewRows(preview)
                }
                Button {
                    previewImport(request)
                } label: {
                    if previewBusy {
                        Label("Inspecting Key…", systemImage: "hourglass")
                    } else {
                        Label(
                            importPreview == nil ? "Preview Key" : "Refresh Preview",
                            systemImage: "key.viewfinder"
                        )
                    }
                }
                .disabled(busy || previewBusy)
            } else {
                if let name = request.importResolvedDisplayName {
                    LabeledContent("Key Name", value: name)
                }
                if let algorithm = request.importResolvedAlgorithm {
                    LabeledContent("Algorithm", value: algorithmTitle(algorithm))
                }
                if let publicKeyBlob = request.publicKeyBlob {
                    LabeledContent("Fingerprint", value: sshFingerprint(publicKeyBlob))
                }
            }
            if let lifetime = request.importLifetimeSeconds {
                LabeledContent(
                    "Requested Lifetime",
                    value: String(
                        localized: "\(lifetime) seconds",
                        comment: "Requested lifetime of an imported SSH identity, in seconds."
                    )
                )
            }
            if request.confirmationRequired {
                Text("The requester requires confirmation for every use.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func importPreviewRows(_ preview: SshPrivateKeyImportPreview) -> some View {
        LabeledContent("Algorithm", value: algorithmTitle(preview.algorithm.rawValue))
        LabeledContent("Fingerprint", value: sshFingerprint(preview.publicKeyBlob))
        if let format = preview.sourceFormat {
            LabeledContent("Format", value: format.rawValue)
        }
        if let comment = preview.importedComment, !comment.isEmpty {
            LabeledContent("Imported Comment", value: comment)
        }
        Text("Confirm this fingerprint before approving the import.")
            .font(.footnote)
            .foregroundStyle(.secondary)
    }

    private func previewImport(_ request: SshProviderRequestRecord) {
        previewBusy = true
        importPreview = nil
        Task {
            do {
                importPreview = try await runtime.previewSshImportRequest(
                    id: request.id,
                    passphrase: importPassphrase.isEmpty ? nil : importPassphrase
                )
                previewBusy = false
            } catch {
                previewBusy = false
                importPassphrase.removeAll(keepingCapacity: false)
                errorMessage = error.localizedDescription
            }
        }
    }

    @ViewBuilder
    private func rememberSection(_ request: SshProviderRequestRecord) -> some View {
        Section("Future Requests") {
            Picker("Approval", selection: $rememberChoice) {
                Text("Always ask").tag(SshApprovalRememberChoice.none)
                Text("Remember this device").tag(SshApprovalRememberChoice.peer)
                if request.destination?.provenance == "VERIFIED_SESSION_BIND",
                   request.destination?.serverHostKeyBlobSha256 != nil {
                    Text("Remember device and host key").tag(SshApprovalRememberChoice.peerAndHost)
                }
            }
        }
    }

    private func approvalFooter(_ request: SshProviderRequestRecord) -> some View {
        HStack(spacing: 12) {
            Button(role: .destructive) {
                act {
                    defer { importPassphrase.removeAll(keepingCapacity: false) }
                    try await runtime.rejectSshRequest(id: request.id)
                }
            } label: {
                Text("Reject").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(busy)

            Button {
                act {
                    defer { importPassphrase.removeAll(keepingCapacity: false) }
                    try await runtime.approveSshRequest(
                        id: request.id,
                        remember: rememberChoice,
                        importDisplayName: importName.trimmingCharacters(in: .whitespacesAndNewlines),
                        importPassphrase: importPassphrase.isEmpty ? nil : importPassphrase
                    )
                }
            } label: {
                if busy { ProgressView().frame(maxWidth: .infinity) }
                else { Text("Approve").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(
                busy || previewBusy ||
                    (request.kind == .importKey &&
                        (importName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || importPreview == nil))
            )
        }
        .padding()
        .background(.bar)
    }

    private func act(_ operation: @escaping () async throws -> Void) {
        busy = true
        Task {
            do {
                try await operation()
                busy = false
            } catch {
                busy = false
                if request?.kind == .importKey {
                    importPassphrase.removeAll(keepingCapacity: false)
                    importPreview = nil
                }
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct SshGenerateKeyView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    @State private var name = String(localized: "iPhone SSH Key")
    @State private var algorithm = SshManagedGenerationAlgorithm.p256
    @State private var rsaBits = 3072
    @State private var busy = false
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section("Key") {
                TextField("Name", text: $name)
                Picker("Algorithm", selection: $algorithm) {
                    ForEach(SshManagedGenerationAlgorithm.allCases) { Text($0.title).tag($0) }
                }
                if algorithm == .rsa {
                    Picker("RSA Size", selection: $rsaBits) {
                        Text("2048 bits").tag(2048)
                        Text("3072 bits").tag(3072)
                        Text("4096 bits").tag(4096)
                    }
                }
            }
            Section {
                Text("The private key will be stored only in this device’s Keychain and is available after the first unlock following a restart.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Generate SSH Key")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Generate") {
                    busy = true
                    Task {
                        do {
                            try await runtime.generateManagedSshKey(
                                displayName: name.trimmingCharacters(in: .whitespacesAndNewlines),
                                algorithm: algorithm,
                                rsaBits: rsaBits
                            )
                            dismiss()
                        } catch {
                            busy = false
                            errorMessage = error.localizedDescription
                        }
                    }
                }
                .disabled(busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .overlay { if busy { ProgressView().controlSize(.large) } }
        .alert("Could Not Generate Key", isPresented: Binding(
            get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } }
        )) { Button("OK", role: .cancel) {} } message: { Text(errorMessage ?? "") }
    }
}

private struct SshLocalImportView: View {
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    let data: Data
    let suggestedName: String?
    @State private var name = ""
    @State private var passphrase = ""
    @State private var preview: SshPrivateKeyImportPreview?
    @State private var previewBusy = false
    @State private var busy = false
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section("Private Key") {
                LabeledContent("Size", value: ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                TextField("Key Name", text: $name)
                SecureField("Passphrase, if encrypted", text: $passphrase)
                if let preview {
                    LabeledContent("Algorithm", value: algorithmTitle(preview.algorithm.rawValue))
                    LabeledContent("Fingerprint", value: sshFingerprint(preview.publicKeyBlob))
                    if let format = preview.sourceFormat {
                        LabeledContent("Format", value: format.rawValue)
                    }
                    if let comment = preview.importedComment, !comment.isEmpty {
                        LabeledContent("Imported Comment", value: comment)
                    }
                    Text("Confirm this fingerprint before importing the key.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button {
                    inspectKey()
                } label: {
                    if previewBusy {
                        Label("Inspecting Key…", systemImage: "hourglass")
                    } else {
                        Label(preview == nil ? "Preview Key" : "Refresh Preview", systemImage: "key.viewfinder")
                    }
                }
                .disabled(busy || previewBusy)
            }
            Section {
                Text("Supported in this version: OpenSSH, PEM, and PKCS#8 private keys. PuTTY PPK files are not supported.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Import SSH Key")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if name.isEmpty { name = suggestedName ?? String(localized: "Imported SSH Key") } }
        .onChange(of: passphrase) { _, _ in preview = nil }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Import") {
                    busy = true
                    Task {
                        do {
                            try await runtime.importManagedSshKey(
                                data: data,
                                passphrase: passphrase.isEmpty ? nil : passphrase,
                                displayName: name.trimmingCharacters(in: .whitespacesAndNewlines)
                            )
                            passphrase.removeAll(keepingCapacity: false)
                            dismiss()
                        } catch {
                            passphrase.removeAll(keepingCapacity: false)
                            preview = nil
                            busy = false
                            errorMessage = error.localizedDescription
                        }
                    }
                }
                .disabled(
                    busy || previewBusy || preview == nil ||
                        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                )
            }
        }
        .overlay { if busy { ProgressView().controlSize(.large) } }
        .alert("Could Not Import Key", isPresented: Binding(
            get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } }
        )) { Button("OK", role: .cancel) {} } message: { Text(errorMessage ?? "") }
    }

    private func inspectKey() {
        previewBusy = true
        preview = nil
        Task {
            do {
                preview = try await runtime.previewManagedSshKey(
                    data: data,
                    passphrase: passphrase.isEmpty ? nil : passphrase
                )
                previewBusy = false
            } catch {
                previewBusy = false
                passphrase.removeAll(keepingCapacity: false)
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct SshPasskeyView: View {
    enum Mode { case create, recover }
    enum RecoveryPhase { case idle, selectingPasskey, lookingUpRecord, ready, manual }
    @EnvironmentObject private var runtime: NotiSyncRuntime
    @Environment(\.dismiss) private var dismiss
    let mode: Mode
    let initialRecoveryRecord: String?
    @State private var name = String(localized: "Passkey SSH Key")
    @State private var recoveryRecord = ""
    @State private var recoverySelection: SshPasskeyRecoverySelection?
    @State private var recoveryPhase = RecoveryPhase.idle
    @State private var recoveryFallbackMessage: String?
    @State private var busy = false
    @State private var errorMessage: String?
    @State private var creationCompleted = false

    var body: some View {
        Form {
            Section("Passkey") {
                TextField("Key Name", text: $name)
                if mode == .recover {
                    recoveryContent
                }
            }
            Section {
                Text(passkeyGuidance)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(mode == .create ? "Create Passkey Key" : "Use Existing Passkey")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let initialRecoveryRecord, !initialRecoveryRecord.isEmpty {
                recoveryRecord = initialRecoveryRecord
                recoveryPhase = .manual
            }
        }
        .task {
            if mode == .recover, recoveryPhase == .idle { await beginAutomaticRecovery() }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            if mode == .create || recoveryPhase == .ready || recoveryPhase == .manual {
                ToolbarItem(placement: .confirmationAction) {
                    Button(mode == .create ? "Create" : "Recover", action: performPrimaryAction)
                        .disabled(primaryActionDisabled)
                }
            }
        }
        .overlay { if busy { ProgressView().controlSize(.large) } }
        .alert("Passkey SSH Key", isPresented: Binding(
            get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {
                if creationCompleted { dismiss() }
            }
        } message: { Text(errorMessage ?? "") }
    }

    @ViewBuilder
    private var recoveryContent: some View {
        switch recoveryPhase {
        case .idle, .selectingPasskey:
            Label("Select the roamed passkey you want to add.", systemImage: "person.badge.key")
        case .lookingUpRecord:
            Label("Looking for its public recovery record in Passwords…", systemImage: "key.viewfinder")
        case .ready:
            Label("The matching passkey and public recovery record are ready.", systemImage: "checkmark.circle")
                .foregroundStyle(.green)
        case .manual:
            if let recoveryFallbackMessage { Text(recoveryFallbackMessage).font(.footnote) }
            TextEditor(text: $recoveryRecord)
                .frame(minHeight: 150)
                .font(.system(.footnote, design: .monospaced))
            Button("Try Automatic Lookup Again") {
                recoverySelection = nil
                recoveryRecord = ""
                recoveryFallbackMessage = nil
                recoveryPhase = .idle
                Task { await beginAutomaticRecovery() }
            }
            .disabled(busy)
        }
    }

    private var passkeyGuidance: String {
        if mode == .create {
            return String(localized: "The passkey may roam through your enabled credential provider. NotiSync stores only its public SSH recovery record.")
        }
        switch recoveryPhase {
        case .ready:
            return String(localized: "Review the key name, then add the verified roamed passkey to this device.")
        case .manual:
            return String(localized: "Paste the public recovery record from NotiSync. The selected or matching roamed passkey is still required.")
        case .idle, .selectingPasskey, .lookingUpRecord:
            return String(localized: "NotiSync first selects the roamed passkey, then looks up its public recovery record. Manual entry remains available if lookup is unavailable.")
        }
    }

    private var primaryActionDisabled: Bool {
        busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            (mode == .recover && recoveryRecord.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private func beginAutomaticRecovery() async {
        guard mode == .recover, recoveryPhase == .idle else { return }
        busy = true
        recoveryPhase = .selectingPasskey
        do {
            let selection = try await runtime.beginSshPasskeyRecovery()
            guard !Task.isCancelled else { return }
            recoverySelection = selection
            recoveryPhase = .lookingUpRecord
            do {
                let lookup = try await runtime.lookupSshPasskeyRecoveryRecord(for: selection)
                guard !Task.isCancelled else { return }
                recoveryRecord = lookup.record
                name = lookup.suggestedName
                recoveryPhase = .ready
                busy = false
            } catch {
                recoveryFallbackMessage = String(localized: "The public recovery record was not found automatically. Paste a recovery record to continue.")
                recoveryPhase = .manual
                busy = false
            }
        } catch {
            recoverySelection = nil
            recoveryFallbackMessage = String(localized: "Automatic passkey selection did not finish. Paste a recovery record to select the matching passkey directly.")
            recoveryPhase = .manual
            busy = false
        }
    }

    private func performPrimaryAction() {
        busy = true
        Task {
            do {
                let displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                if mode == .create {
                    let recoverySaved = try await runtime.createSshPasskey(displayName: displayName)
                    if !recoverySaved {
                        creationCompleted = true
                        busy = false
                        errorMessage = String(localized: "The passkey SSH key was created, but its public recovery record was not saved to Passwords. You can retry or share it from the key details.")
                        return
                    }
                } else {
                    let record = recoveryRecord.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let recoverySelection {
                        try await runtime.recoverSshPasskey(
                            selection: recoverySelection,
                            recoveryRecordJSON: record,
                            displayName: displayName
                        )
                    } else {
                        try await runtime.recoverSshPasskey(
                            recoveryRecordJSON: record,
                            displayName: displayName
                        )
                    }
                }
                dismiss()
            } catch {
                busy = false
                errorMessage = error.localizedDescription
            }
        }
    }
}

private enum SshUIError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        guard case .message(let value) = self else { return nil }
        return value
    }
}

private func algorithmTitle(_ raw: String) -> String {
    switch raw {
    case "SSH_ED25519": "Ed25519"
    case "SSH_RSA": "RSA"
    case "ECDSA_NISTP256": "ECDSA P-256"
    case "WEBAUTHN_SK_ECDSA_NISTP256": String(localized: "Passkey ECDSA-SK")
    default: raw
    }
}

private func destinationProvenanceTitle(_ raw: String) -> String {
    switch raw {
    case "VERIFIED_SESSION_BIND": String(localized: "Verified session binding")
    case "SIGNED_USERAUTH": String(localized: "Signed SSH user authentication")
    case "KNOWN_HOSTS_MATCH": String(localized: "Known hosts match")
    case "PROCESS_HINT": String(localized: "Process hint")
    case "UNKNOWN": String(localized: "Unknown")
    default: raw
    }
}

private func signatureAlgorithmTitle(_ raw: String) -> String {
    switch raw {
    case "SSH_ED25519": "Ed25519"
    case "RSA_SHA2_256": "RSA SHA-256"
    case "RSA_SHA2_512": "RSA SHA-512"
    case "ECDSA_NISTP256": "ECDSA P-256"
    case "WEBAUTHN_SK_ECDSA_NISTP256": String(localized: "Passkey ECDSA-SK")
    case "RSA_SHA1_LEGACY": String(localized: "RSA SHA-1 (legacy)")
    default: raw
    }
}

private func connectionDirectionTitle(_ raw: String) -> String {
    switch raw {
    case "DIRECT": String(localized: "Direct")
    case "FORWARDED": String(localized: "Forwarded")
    case "UNKNOWN": String(localized: "Unknown")
    default: raw
    }
}

private func processSourceTitle(_ raw: String) -> String {
    switch raw {
    case "PEER_CREDENTIALS": String(localized: "Local peer credentials")
    case "NAMED_PIPE_CLIENT_PID": String(localized: "Named-pipe client process")
    case "CURRENT_PROCESS": String(localized: "Current process")
    case "BRIDGE_REPORTED": String(localized: "Desktop bridge report")
    case "UNAVAILABLE": String(localized: "Unavailable")
    default: raw
    }
}

private func approvalMethodTitle(_ raw: String?) -> String {
    switch raw {
    case "MATCHED_PEER", "MATCHED_PEER_HOST_KEY", "MATCHED_APPLICATION_PROCESS":
        String(localized: "Auto approved by remembered authorization")
    case "CREATED_PEER", "CREATED_PEER_HOST_KEY", "CREATED_APPLICATION_PROCESS", "NONE":
        String(localized: "Manually approved")
    case "NOT_ALLOWED_FOR_KEY":
        String(localized: "Manually approved; remembering unavailable")
    case nil:
        String(localized: "Approved")
    default:
        raw ?? String(localized: "Approved")
    }
}

private func sshFingerprint(_ blob: Data) -> String {
    sshFingerprintDigest(NSHash.sha256(blob))
}

private func sshPublicKeyText(_ key: SshProviderKeyRecord) -> String {
    let wireName: String = switch key.algorithm {
    case "SSH_ED25519": "ssh-ed25519"
    case "SSH_RSA": "ssh-rsa"
    case "ECDSA_NISTP256": "ecdsa-sha2-nistp256"
    case "WEBAUTHN_SK_ECDSA_NISTP256": "sk-ecdsa-sha2-nistp256@openssh.com"
    default: key.algorithm
    }
    return "\(wireName) \(key.publicKeyBlob.base64EncodedString()) \(key.displayName)"
}

private func passkeyBackupTitle(_ key: SshProviderKeyRecord) -> String {
    guard key.backupEligible == true else { return String(localized: "Not eligible for sync") }
    return key.backupState == true
        ? String(localized: "Backed up by credential provider")
        : String(localized: "Eligible; not currently backed up")
}

private func sshFingerprintDigest(_ digest: Data) -> String {
    "SHA256:" + digest.base64EncodedString().trimmingCharacters(in: CharacterSet(charactersIn: "="))
}

private func shortId(_ value: String) -> String {
    value.count > 12 ? String(value.prefix(12)) + "…" : value
}

private func dateText(_ millis: Int64) -> String {
    Date(timeIntervalSince1970: TimeInterval(millis) / 1_000).formatted(date: .abbreviated, time: .shortened)
}

private func statusSymbol(_ request: SshProviderRequestRecord) -> String {
    switch request.status {
    case .pendingReview: "exclamationmark.shield"
    case .responsePendingSend: "paperplane"
    case .sent: request.outcome == .rejected ? "xmark.shield" : "checkmark.shield"
    case .cancelled: "slash.circle"
    case .expired: "clock.badge.xmark"
    }
}

private func statusColor(_ request: SshProviderRequestRecord) -> Color {
    switch request.status {
    case .pendingReview: .orange
    case .responsePendingSend: .blue
    case .sent: request.outcome == .rejected || request.outcome == .failed ? .red : .green
    case .cancelled, .expired: .secondary
    }
}

private func statusHeading(_ request: SshProviderRequestRecord) -> String {
    switch request.status {
    case .pendingReview: String(localized: "Waiting for your approval")
    case .responsePendingSend: String(localized: "Sending response")
    case .sent:
        switch request.outcome {
        case .signed: String(localized: "Request signed")
        case .imported: String(localized: "Key imported")
        case .alreadyPresent: String(localized: "Key already present")
        case .rejected: String(localized: "Request rejected")
        case .failed: String(localized: "Request failed")
        case .cancelled: String(localized: "Request cancelled")
        case .expired: String(localized: "Request expired")
        case nil: String(localized: "Request completed")
        }
    case .cancelled: String(localized: "Request cancelled")
    case .expired: String(localized: "Request expired")
    }
}
