package net.extrawdw.apps.notisync.sshkeyprovider

/** Structured diagnostics for localization at the Android UI boundary. */
internal sealed class SshWebAuthnException : IllegalArgumentException() {
    class UntrustedOrigin(val received: String, val expected: String) : SshWebAuthnException()

    class IncompatibleClientData(val clientDataJson: String) : SshWebAuthnException()

    class InvalidAssertionSignature : SshWebAuthnException()
}
