import os
import UIKit
import UserNotifications
import UserNotificationsUI

private let log = Logger(subsystem: "net.extrawdw.apps.NotiSync", category: "NotificationContent")

/// Expanded-notification UI for optional rich content. It deliberately leaves the notification's native
/// action row alone, including iOS's own text-input flow for remote-input actions.
final class NotificationContentViewController: UIViewController, UNNotificationContentExtension {
    private enum Layout {
        static let appIconAttachmentIdentifier = "notisync.appicon"
        static let horizontalInset: CGFloat = 12
        static let appIconSize: CGFloat = 44
        static let appIconRowHeight: CGFloat = 60
        static let appIconCornerRadius: CGFloat = 10
        static let maxTextHeight: CGFloat = 220
    }

    private static let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    private let stackView = UIStackView()
    private let appIconRow = UIView()
    private let appIconView = UIImageView()
    private let textView = UITextView()
    private var textHeightConstraint: NSLayoutConstraint?

    override func viewDidLoad() {
        super.viewDidLoad()
        log.info("content extension viewDidLoad")
        view.backgroundColor = .clear

        stackView.axis = .vertical
        stackView.alignment = .fill
        stackView.spacing = 0
        stackView.backgroundColor = .clear
        stackView.translatesAutoresizingMaskIntoConstraints = false
        stackView.isHidden = true
        view.addSubview(stackView)

        appIconRow.backgroundColor = .clear
        appIconRow.translatesAutoresizingMaskIntoConstraints = false
        appIconRow.isHidden = true

        appIconView.backgroundColor = .secondarySystemFill
        appIconView.contentMode = .scaleAspectFill
        appIconView.clipsToBounds = true
        appIconView.layer.cornerCurve = .continuous
        appIconView.layer.cornerRadius = Layout.appIconCornerRadius
        appIconView.translatesAutoresizingMaskIntoConstraints = false
        appIconRow.addSubview(appIconView)

        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.dataDetectorTypes = [.link]
        textView.font = .preferredFont(forTextStyle: .body)
        textView.textContainerInset = UIEdgeInsets(top: 8, left: Layout.horizontalInset, bottom: 8,
                                                   right: Layout.horizontalInset)
        textView.textContainer.lineFragmentPadding = 0
        textView.translatesAutoresizingMaskIntoConstraints = false
        textView.isHidden = true
        stackView.addArrangedSubview(appIconRow)
        stackView.addArrangedSubview(textView)

        let textHeightConstraint = textView.heightAnchor.constraint(equalToConstant: 0)
        self.textHeightConstraint = textHeightConstraint

        NSLayoutConstraint.activate([
            stackView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stackView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stackView.topAnchor.constraint(equalTo: view.topAnchor),

            appIconRow.heightAnchor.constraint(equalToConstant: Layout.appIconRowHeight),
            appIconView.leadingAnchor.constraint(equalTo: appIconRow.leadingAnchor,
                                                 constant: Layout.horizontalInset),
            appIconView.centerYAnchor.constraint(equalTo: appIconRow.centerYAnchor),
            appIconView.widthAnchor.constraint(equalToConstant: Layout.appIconSize),
            appIconView.heightAnchor.constraint(equalToConstant: Layout.appIconSize),
            textHeightConstraint,
        ])
        preferredContentSize = .zero
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if !stackView.isHidden { updatePreferredContentSize() }
    }

    func didReceive(_ notification: UNNotification) {
        let content = notification.request.content
        let body = content.body.trimmingCharacters(in: .whitespacesAndNewlines)
        let appIcon = appIconImage(from: content)
        let hasLinkBody = bodyContainsLink(body)

        appIconView.image = appIcon
        appIconRow.isHidden = appIcon == nil
        textView.text = hasLinkBody ? body : ""
        textView.isHidden = !hasLinkBody

        guard appIcon != nil || hasLinkBody else {
            stackView.isHidden = true
            collapse()
            return
        }

        stackView.isHidden = false
        updatePreferredContentSize()
        log.info("content extension rendered category=\(content.categoryIdentifier, privacy: .public) appIcon=\(appIcon != nil) linkBody=\(hasLinkBody)")
    }

    func didReceive(_ response: UNNotificationResponse,
                    completionHandler completion: @escaping (UNNotificationContentExtensionResponseOption) -> Void) {
        completion(.dismissAndForwardAction)
    }

    private func bodyContainsLink(_ body: String) -> Bool {
        guard !body.isEmpty, let detector = Self.detector else { return false }
        let range = NSRange(body.startIndex..<body.endIndex, in: body)
        return detector.firstMatch(in: body, options: [], range: range) != nil
    }

    private func appIconImage(from content: UNNotificationContent) -> UIImage? {
        guard let attachment = content.attachments.first(where: {
            $0.identifier == Layout.appIconAttachmentIdentifier
        }) else { return nil }

        let scoped = attachment.url.startAccessingSecurityScopedResource()
        defer {
            if scoped { attachment.url.stopAccessingSecurityScopedResource() }
        }
        if let image = UIImage(contentsOfFile: attachment.url.path) {
            return image
        }
        guard let data = try? Data(contentsOf: attachment.url) else { return nil }
        return UIImage(data: data)
    }

    private func collapse() {
        appIconView.image = nil
        appIconRow.isHidden = true
        textView.isHidden = true
        textView.text = ""
        textView.isScrollEnabled = false
        textHeightConstraint?.constant = 0
        preferredContentSize = .zero
    }

    private func updatePreferredContentSize() {
        let width = max(view.bounds.width, 1)
        var height: CGFloat = appIconRow.isHidden ? 0 : Layout.appIconRowHeight
        if textView.isHidden {
            textView.isScrollEnabled = false
            textHeightConstraint?.constant = 0
        } else {
            let fitting = textView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
            let textHeight = min(max(fitting.height, 44), Layout.maxTextHeight)
            textView.isScrollEnabled = fitting.height > textHeight
            textHeightConstraint?.constant = textHeight
            height += textHeight
        }
        let size = CGSize(width: width, height: height)
        if preferredContentSize != size {
            preferredContentSize = size
        }
    }
}
