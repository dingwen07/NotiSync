import SwiftUI

extension View {
    /// Uses Liquid Glass on iOS 26 and preserves the equivalent bordered hierarchy on earlier releases.
    @ViewBuilder
    func nativeGlassButton(prominent: Bool = false) -> some View {
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
