# Signal-Android UI Screens & Navigation Tree

Tree of primary UI screens and where each lives under:

```
app/src/main/java/org/thoughtcrime/securesms/
```

Paths below are **relative to that package**. Navigation XML graphs live under `app/src/main/res/navigation/`.

---

## Mental model

```
Launcher (RoutingActivity → MainActivity)
        │
        ▼
PassphraseRequiredActivity gate
  (unlock / register / restore / PIN / profile if needed)
        │
        ▼
MainActivity  =  AppScaffold
  ├─ List pane tabs:  Chats | Calls | Stories
  └─ Detail pane:     Conversation / Call details / Story settings…
```

- **Normal chat open** uses the Main **detail pane** (`ConversationFragment`), not a separate Activity.
- `ConversationActivity` mostly **redirects into MainActivity** (except notification bubbles).
- Many features are separate **Activities** or **NavHost graphs** launched from Main, conversation, or Settings.

---

## 1. Launch & gate

```
ApplicationContext.java                          [Application — no UI]

RoutingActivity  (manifest activity-alias → MainActivity)
│  (also RoutingActivityAlt* for alternate app icons)
│
└─ MainActivity.kt                               extends PassphraseRequiredActivity
   │
   └─ [GATE] PassphraseRequiredActivity.java
      │
      ├─ PassphraseCreateActivity.java           [first-time passphrase]
      ├─ PassphrasePromptActivity.java           [screen lock unlock]
      ├─ migrations/ApplicationMigrationActivity.java
      │
      ├─ registration/ui/RegistrationActivity.kt
      │    graph: res/navigation/registration_v3.xml
      │    ├─ welcome/WelcomeFragment
      │    ├─ permissions/GrantPermissionsFragment
      │    ├─ phonenumber/EnterPhoneNumberFragment
      │    │    ├─ countrycode/CountryCodeFragment
      │    │    ├─ captcha/RegistrationCaptchaFragment
      │    │    └─ reregisterwithpin/ReRegisterWithPinFragment
      │    ├─ entercode/EnterCodeFragment
      │    ├─ registrationlock/RegistrationLockFragment
      │    │    └─ accountlocked/AccountLockedFragment
      │    ├─ link/RegisterLinkDeviceQrFragment
      │    └─ restore/…
      │         ├─ RestoreViaQrFragment
      │         ├─ SelectManualRestoreMethodFragment
      │         ├─ EnterBackupKeyFragment
      │         └─ local/RestoreLocalBackupFragment
      │              └─ on success → MainActivity
      │
      ├─ restore/RestoreActivity.kt$
      │    graph: res/navigation/restore.xml
      │    ├─ selection/SelectRestoreMethodFragment
      │    ├─ enterbackupkey/PostRegistrationEnterBackupKeyFragment
      │    ├─ choosebackup/ChooseBackupFragment
      │    ├─ local/ / restorelocalbackup/ …
      │    └─ Device transfer (new device)
      │         └─ devicetransfer/newdevice/
      │              NewDeviceTransferInstructionsFragment
      │              → Setup → Transfer → Complete
      │
      ├─ pin/PinRestoreActivity.java
      │    graph: pin_restore.xml
      │    ├─ PinRestoreEntryFragment
      │    └─ PinRestoreLockedFragment
      │
      ├─ profiles/edit/CreateProfileActivity
      │    graph: create_profile.xml
      │
      ├─ lock/v2/CreateSvrPinActivity
      │    graph: create_kbs_pin.xml
      │    ├─ CreateSvrPinFragment
      │    └─ ConfirmSvrPinFragment
      │
      ├─ devicetransfer/olddevice/OldDeviceTransferActivity
      │    graph: old_device_transfer.xml
      │
      └─ components/settings/app/changenumber/ChangeNumberLockActivity

DeepLinkEntryActivity (deeplinks/)  →  MainActivity.clearTop()
```

---

## 2. Main shell (after gate)

```
MainActivity.kt
└─ window/AppScaffold.kt
   │
   ├─ LIST PANE  — main/MainNavigation.kt
   │  tabs: CHATS | CALLS | STORIES | ARCHIVE
   │
   └─ DETAIL PANE  — Navigation3 stacks
      chats/ChatsNavigation.kt
      calls/CallsNavigation.kt
      stories/StoriesNavigation.kt
```

### 2.1 Chats tab

```
conversationlist/ConversationListFragment
│
├─ conversationlist/ConversationListArchiveFragment   [ARCHIVE]
│
├─ Toolbar / overflow
│  ├─ → App Settings          (see §4)
│  ├─ → starred/StarredMessagesActivity
│  ├─ → New group             (see §5 Groups)
│  ├─ → Invite / proxy / notification profiles…
│
├─ FAB  → conversation/NewConversationActivity
│           └─ opens chat via ConversationIntents → Main detail
│
├─ Camera FAB  → mediasend/… (see §6)
│
└─ Row click
     → MainNavigator.goToConversation()
     → MainNavigationDetailLocation.Conversation
     → conversation/v2/ConversationFragment          ★ primary chat UI
```

### 2.2 Conversation detail (primary chat)

```
conversation/v2/ConversationFragment
│
├─ Nested / launched from conversation
│  ├─ Conversation settings
│  │    ├─ Nav3: Chats.ConversationSettings
│  │    │    → components/settings/conversation/
│  │    │      graph: conversation_settings.xml
│  │    │      ├─ ConversationSettingsFragment
│  │    │      ├─ SoundsAndNotificationsSettingsFragment
│  │    │      ├─ PermissionsSettingsFragment
│  │    │      ├─ recipients/ui/sharablegrouplink/…
│  │    │      └─ groups/ member search / labels…
│  │    └─ or components/settings/conversation/ConversationSettingsActivity
│  │
│  ├─ messagedetails/MessageDetailsFragment
│  ├─ mediasend/v2/MediaSelectionActivity  (or mediasend/v3/MediaSendV3Activity)
│  ├─ mediapreview/MediaPreviewV2Activity
│  ├─ mediaoverview/MediaOverviewActivity
│  ├─ giph/ui/GiphyActivity
│  ├─ conversation/mutiselect/forward/MultiselectForwardActivity
│  ├─ revealable/ViewOnceMessageActivity
│  ├─ verify/VerifyIdentityActivity
│  ├─ stickers/preview/StickerPackPreviewActivity
│  ├─ stickers/manage/StickerManagementActivity
│  ├─ contactshare/ContactShareEditActivity
│  ├─ contactshare/SharedContactDetailsActivity
│  ├─ maps/PlacePickerActivity
│  ├─ stories/viewer/StoryViewerActivity
│  ├─ groups/memberlabel/MemberLabelActivity
│  ├─ components/webrtc/v2/WebRtcCallActivity
│  ├─ wallpaper/ChatWallpaperActivity
│  └─ nicknames/NicknameActivity
│
└─ Alternate conversation hosts (not normal in-app path)
   ├─ conversation/v2/ConversationActivity
   │    └─ if not bubble → relaunches MainActivity with conversation intent, finishes
   ├─ conversation/BubbleConversationActivity
   └─ conversation/ConversationPopupActivity
```

**How a chat opens (normal path):**

```
ConversationListFragment.onConversationClicked
  → MainNavigator.goToConversation(...)
  → ConversationIntents → ConversationArgs
  → MainNavigationViewModel.goTo(Conversation)
  → ConversationFragment (conversation/v2/)
```

### 2.3 Calls tab

```
calls/log/CallLogFragment
│
├─ FAB  → calls/new/NewCallActivity
│           └─ may use recipients/ui/findby/FindByActivity
│
└─ Call link row
     → detail: calls/links/details/CallLinkDetailsScreen
     → edit name: calls/links/EditCallLinkNameScreen

Incoming / outgoing call UI
  → components/webrtc/v2/WebRtcCallActivity
  → service/webrtc/ (managers; not screens)
  → messagerequests/CalleeMustAcceptMessageRequestActivity  [blocked by request]
```

### 2.4 Stories tab

```
stories/landing/StoriesLandingFragment
│
├─ Story row     → stories/viewer/StoryViewerActivity
├─ My Stories    → detail Stories.MyStories
│                    stories/my/MyStoriesFragment
│                    (also stories/my/MyStoriesActivity legacy host)
├─ Privacy       → detail Stories.PrivacySettings
│                    graph: story_privacy_settings.xml (+ my_story / private_story)
│                    ├─ stories/settings/story/StoriesPrivacySettingsFragment
│                    ├─ stories/settings/group/GroupStorySettingsFragment
│                    ├─ stories/settings/my/MyStorySettingsFragment
│                    │    └─ stories/settings/privacy/ AllExcept / OnlyShareWith
│                    └─ stories/settings/custom/PrivateStorySettingsFragment
│                         ├─ EditStoryNameFragment
│                         └─ AddViewersFragment
├─ Archive       → detail Stories.Archive
│                    stories/archive/StoryArchiveScreen
│                    (also stories/archive/StoryArchiveActivity)
└─ Camera FAB    → mediasend/… in story mode
```

---

## 3. Media send

```
mediasend/v2/MediaSelectionActivity
  graph: res/navigation/media.xml
  ├─ MediaCaptureFragment
  ├─ MediaSelectionGalleryFragment
  ├─ MediaReviewFragment
  └─ TextStoryPostCreationFragment          [stories]

mediasend/v3/MediaSendV3Activity            [newer pipeline / flag]
mediasend/v2/stories/StoriesMultiselectForwardActivity
mediasend/AvatarSelectionActivity
```

Reached from: Chats camera FAB, Stories camera FAB, conversation attach, profile avatar.

---

## 4. App Settings

Opened from Main chats toolbar / avatar.

```
components/settings/app/AppSettingsActivity
  NavHost: app_settings_with_change_number.xml
  │
  └─ AppSettingsFragment                     [settings home]
     │
     ├─ Account
     │  ├─ …/account/AccountSettingsFragment
     │  ├─ profiles/manage/EditProfileActivity
     │  │    graph: edit_profile.xml
     │  ├─ Change number
     │  │    include: app_settings_change_number.xml
     │  │    ChangeNumberFragment → phone → confirm → verify → captcha → code…
     │  ├─ preferences/AdvancedPinSettingsFragment
     │  ├─ delete/DeleteAccountFragment
     │  ├─ …/account/export/ExportAccountDataFragment
     │  └─ → OldDeviceTransferActivity
     │
     ├─ Linked devices
     │  └─ linkdevice/LinkDeviceFragment
     │       (+ AddLinkDevice / EditDeviceName sheets)
     │
     ├─ Username link
     │  graph: username_link_settings.xml
     │  └─ UsernameLinkSettingsFragment
     │       → UsernameQrScannerActivity
     │       → QrImageSelectionActivity
     │
     ├─ Donations
     │  ├─ …/subscription/manage/ManageDonationsFragment
     │  ├─ …/subscription/donate/CheckoutFlowActivity
     │  │    graph: checkout.xml
     │  └─ manage_badges.xml (badges)
     │
     ├─ Appearance
     │  ├─ AppearanceSettingsFragment
     │  ├─ wallpaper/ChatWallpaperActivity
     │  │    graph: chat_wallpaper.xml
     │  │    └─ wallpaper/crop/WallpaperCropActivity
     │  │         WallpaperImageSelectionActivity
     │  └─ App icon selection / tutorial fragments
     │
     ├─ Chats
     │  ├─ …/chats/ChatsSettingsFragment
     │  ├─ reactions/edit/EditReactionsActivity
     │  └─ Chat folders (Create / Choose chats fragments)
     │
     ├─ Stories privacy          [same graph as Main stories detail]
     │
     ├─ Notifications
     │  └─ NotificationsSettingsFragment
     │       └─ Notification profiles (create / edit / schedule…)
     │
     ├─ Privacy
     │  graph: privacy_settings.xml
     │  ├─ PrivacySettingsFragment
     │  ├─ blocked/BlockedUsersActivity
     │  ├─ AdvancedPrivacySettingsFragment
     │  ├─ PhoneNumberPrivacySettingsFragment
     │  ├─ ScreenLockSettingsFragment
     │  └─ Expire timer (app_settings_expire_timer.xml)
     │
     ├─ Backups
     │  ├─ …/backups/BackupsSettingsFragment
     │  ├─ RemoteBackupsSettingsFragment → BackupKeyDisplayFragment
     │  ├─ Local backups preferences
     │  ├─ backup/v2/ui/subscription/MessageBackupsCheckoutActivity
     │  └─ backup/v2/ui/verify/VerifyBackupKeyActivity
     │
     ├─ Data & storage
     │  ├─ DataAndStorageSettingsFragment
     │  ├─ ManageStorageSettingsFragment
     │  └─ preferences/EditProxyActivity
     │       graph: edit_proxy.xml
     │
     ├─ Payments
     │  └─ payments/preferences/PaymentsActivity
     │       graphs: payments_preferences.xml (+ transfer/create/backup)
     │       Home → activity list → details → add money / currency / deactivate…
     │
     ├─ Help
     │  ├─ HelpSettingsFragment / HelpFragment
     │  └─ logsubmit/SubmitDebugLogActivity
     │
     ├─ InviteFragment
     ├─ AppUpdatesSettingsFragment
     ├─ LabsSettingsFragment
     └─ InternalSettingsFragment (+ playgrounds)
```

Typed routes: `components/settings/app/routes/AppSettingsRoute.kt`.

---

## 5. Groups

```
groups/ui/creategroup/CreateGroupActivity
  graph: create_group.xml
  └─ details/AddGroupDetailsActivity

From conversation / group settings:
  groups/ui/addmembers/AddMembersActivity
  groups/ui/addtogroup/AddToGroupsActivity
  groups/ui/chooseadmin/ChooseNewAdminActivity
  groups/ui/invitesandrequests/ManagePendingAndRequestingMembersActivity
  groups/ui/incommon/GroupsInCommonActivity
  groups/memberlabel/MemberLabelActivity
```

Often started from Main “New group” or `NewConversationActivity`.

---

## 6. Sharing, contacts, system entry

```
sharing/v2/ShareActivity                     [ACTION_SEND / SEND_MULTIPLE]
  └─ sharing/interstitial/ShareInterstitialActivity

SystemContactsEntrypointActivity             [system contacts]
PushContactSelectionActivity
search/SingleContactSelectionActivity
recipients/ui/findby/FindByActivity
DeviceProvisioningActivity                   [link device / provisioning]
ShortcutLauncherActivity                     [home shortcuts → chat]
```

---

## 7. Forced / special flows

```
ratelimit/RecaptchaProofActivity
megaphone/ClientDeprecatedActivity
lock/v2/SvrMigrationActivity
  graph: kbs_migration.xml
PassphraseChangeActivity
registration/olddevice/QuickTransferOldDeviceActivity
registration/ui/restore/RemoteRestoreActivity
AvatarPreviewActivity
avatar/photo/PhotoEditorActivity
```

---

## 8. Quick “where is this screen?”


| Screen                | Folder                                                |
| --------------------- | ----------------------------------------------------- |
| Home / chat list      | `conversationlist/`                                   |
| One chat              | `conversation/v2/ConversationFragment`                |
| Main shell            | `MainActivity.kt`, `main/`, `window/AppScaffold.kt`   |
| Calls log             | `calls/log/`                                          |
| New call              | `calls/new/`                                          |
| In-call UI            | `components/webrtc/v2/`                               |
| Stories landing       | `stories/landing/`                                    |
| Story viewer          | `stories/viewer/`                                     |
| App settings          | `components/settings/app/`                            |
| Conversation settings | `components/settings/conversation/`                   |
| Registration          | `registration/ui/`                                    |
| Restore               | `restore/`                                            |
| Media send            | `mediasend/v2/` (and `v3/`)                           |
| Payments              | `payments/preferences/`                               |
| Stickers              | `stickers/`                                           |
| Wallpaper             | `wallpaper/`                                          |
| Backups UI            | `components/settings/app/…/backups/`, `backup/v2/ui/` |
| Groups create         | `groups/ui/creategroup/`                              |


---

## 9. Navigation XML index


| Graph                                                  | Typical host                  |
| ------------------------------------------------------ | ----------------------------- |
| `registration_v3.xml`                                  | `RegistrationActivity`        |
| `restore.xml`                                          | `RestoreActivity`             |
| `pin_restore.xml`                                      | `PinRestoreActivity`          |
| `create_profile.xml`                                   | `CreateProfileActivity`       |
| `create_kbs_pin.xml`                                   | `CreateSvrPinActivity`        |
| `old_device_transfer.xml`                              | `OldDeviceTransferActivity`   |
| `media.xml`                                            | `MediaSelectionActivity`      |
| `conversation_settings.xml`                            | Conversation settings NavHost |
| `app_settings_with_change_number.xml`                  | `AppSettingsActivity`         |
| `app_settings_change_number.xml`                       | Change-number flow            |
| `privacy_settings.xml`                                 | Privacy section               |
| `story_privacy_settings.xml`                           | Stories privacy               |
| `my_story_settings.xml` / `private_story_settings.xml` | My / private story            |
| `payments_*.xml`                                       | `PaymentsActivity`            |
| `checkout.xml`                                         | `CheckoutFlowActivity`        |
| `edit_profile.xml` / `edit_proxy.xml`                  | Profile / proxy               |
| `chat_wallpaper.xml`                                   | Wallpaper                     |
| `create_group.xml`                                     | Create group                  |
| `username_link_settings.xml`                           | Username link                 |
| `manage_badges.xml`                                    | Badges                        |


---

*Companion to `docs/signal-android-architecture.md` · documented Jul 2026*