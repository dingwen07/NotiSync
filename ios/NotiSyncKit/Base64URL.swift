import Foundation

nonisolated extension Data {
    /// Unpadded Base64URL, with the wire alphabet mapped as ASCII bytes.
    func base64URLEncoded() -> String {
        let bytes = base64EncodedString().utf8.compactMap { byte -> UInt8? in
            switch byte {
            case 43: 45 // + -> -
            case 47: 95 // / -> _
            case 61: nil // omit padding
            default: byte
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}
