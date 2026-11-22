# iOS bridge

[Documentation](README.md) · [Getting started](getting-started.md)

Use a nearby Android phone to mirror iPhone notifications and control iPhone media playback over
Bluetooth Low Energy. The iPhone provides notifications through Apple Notification Center Service
(ANCS) and Now Playing information and controls through Apple Media Service (AMS). You can use
the bridge on the Android phone alone or forward what it receives to other trusted devices.

## Connect the phones

On Android, open NotiSync’s **iPhone** tab, turn on **iPhone over Bluetooth**, and grant **Nearby
devices** permission if asked. Leave the tab open and follow the setup method it offers. Both
methods use Android’s Companion Device Manager (CDM) to request access to your specific iPhone.

### Through iPhone Bluetooth Settings

This method works without installing NotiSync on the iPhone.

1. On the iPhone, open **Settings → Bluetooth** and tap the Android phone under **Other Devices**.
2. When Android asks whether NotiSync can access the device, check
   that the prompt shows your iPhone and allow access. If several devices connect at once, NotiSync
   first asks which incoming device is yours.
3. Confirm the Bluetooth pairing code on both phones and allow **Share System Notifications** on
   the iPhone when prompted.

### Through the iOS App

On Android 17, this is the default for Xiaomi, Redmi, POCO, vivo, and iQOO phones that report a
Qualcomm chip. You can still select **Bluetooth Settings** and confirm the warning.
You can also select **iOS App** under **Pair your iPhone** on Android
if setup through Bluetooth Settings leaves it waiting.

1. On the iPhone, open **NotiSync → Settings → Android Bluetooth Bridge** and select your Android
   phone under **Nearby devices**. Allow NotiSync to use Bluetooth if iOS asks.
2. Accept the Bluetooth pairing request and allow notification sharing on the iPhone. On Android,
   approve the CDM device-access prompt for that iPhone. If NotiSync asks which iPhone to use for
   the bridge, confirm your iPhone’s name.

With either method, **Sharing notifications** on Android means the bridge has subscribed to ANCS.
Keep the bridge enabled. CDM access lets Android wake NotiSync when it detects the iPhone nearby.
With app-assisted setup, the iOS App also remembers the Android phone and reconnects when it
returns to range.

## Mirror notifications with ANCS

1. Generate a notification on the iPhone from an app you want to mirror. Apps appear in the Android
   **iPhone** tab as iOS shares their notifications.
2. Turn on that app in the **iPhone** tab. Enable **Mirror to this phone** for local display,
   **Mirror to my other devices** for forwarding, or both. To forward, first pair the Android bridge
   with the intended recipients. [Device pairing](getting-started.md#device-pairing)
3. Generate a second notification from the same app to test delivery. Notifications already present
   when the iPhone reconnects can appear quietly on the bridge phone. Other devices receive new
   notifications.

Mirrored notifications can include the title, message, and actions the iPhone makes available.
For example, an incoming call can offer **Accept** and **Decline**. Dismissing a mirrored
notification can also clear it on the iPhone when iOS permits it. [Apple’s ANCS specification](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html)

## Control playback with AMS

On Android, leave **Mirror media playback** enabled and start playing a track or podcast on the
iPhone. **Mirror to this phone** displays a Now Playing card on the bridge. **Mirror to my other
devices** can also send the card to other trusted Android devices. Playback stays on the iPhone
and its selected audio output.

The card updates as tracks change and can show the title, artist, album, and playback position.
Its buttons reflect the commands the current player supports, such as play, pause, next track,
and previous track. A button press on the mirrored card controls that player on the iPhone.
[Apple’s AMS specification](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Specification/Specification.html)

## If something is missing

- **The bridge switched to iOS App setup.** If Bluetooth repeatedly goes down just after the
  phone starts advertising, NotiSync saves this setup choice and posts a notification. Follow the
  iOS App steps above. If the same problem recurs in app-assisted mode, NotiSync turns the
  bridge off and notifies you. Choosing **Continue** when returning to Bluetooth Settings clears
  the recorded incident. Listed phones still warn each time you switch back.
- **Connected, but notifications aren’t shared.** On the iPhone, open **Settings → Bluetooth**, tap
  the information button beside the Android phone, and enable **Share System Notifications**.
  Then tap **Retry notification sharing** in NotiSync on Android.
- **An iPhone app is missing from the list.** Check that the app can deliver notifications on the
  iPhone and that the active Focus allows them. Generate a new notification while the bridge shows
  **Sharing notifications**.
- **Notifications work but there is no media card.** Check **Mirror media playback** and the
  destination switch, then start a track with a title or artist on the iPhone.
