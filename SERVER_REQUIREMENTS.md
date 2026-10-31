# Love Android Calling App - Architecture & Server Requirements

## 1. Client-Side Features (Included in this APK)
- **UI & UX:** Jetpack Compose Material 3 Light Theme (Blue & White).
- **Navigation:** Home, Contacts, Dialpad, and Settings tabs.
- **Dialer:** Interactive numeric keypad with digit clearing and string construction.
- **SIM Integration:** Native `Intent.ACTION_DIAL` with encoded `tel:` scheme for dialing cellular carrier networks.
- **Preview System:** Labelled call and group-call preview states with mute and speaker toggles.

---

## 2. External Server Requirements (Required to make calls outside the UI preview)

1. **SIP Registrar & PBX Server** (e.g. Asterisk, Kamailio, FreeSWITCH):
   - Required for user accounts, SIP registrations, directory lookup, and PSTN carrier termination.
2. **WebRTC SFU / Media Server** (e.g. LiveKit, Janus, mediasoup):
   - Required for multi-user conference mixing and audio routing for 3 to 5 participants.
3. **STUN & TURN NAT Traversal Relay** (e.g. Coturn):
   - Required to traverse mobile carrier symmetric NATs and firewalls.
4. **Push Notification Server** (Google Firebase Cloud Messaging / FCM):
   - Required for high-priority background wake-up on incoming calls when the app is asleep.
