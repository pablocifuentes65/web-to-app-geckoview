# GeckoView plugin compatibility — audit of the current fork

Status: investigation branch; no compatibility fix is claimed yet.

## What is already present

The current `main` branch already contains an initial GeckoView plugin path:

- `EngineViewFactory.create()` passes the resolved plugin payloads to `GeckoViewEngine.setPluginPayloads()`.
- `GeckoViewEngine` filters payloads through `GeckoPluginHost.runnable()`, creates a `PluginSession`, and injects page code after `onPageStop`.
- `GeckoPluginHost` currently accepts HCJ and USERSCRIPT records, and explicitly excludes CHROME_EXTENSION records.
- `GeckoViewEngine.setupPromptDelegate()` routes three prompt channels to `GeckoPluginHost.handlePrompt()`: config, badge, and panel messages.
- There is an existing unit test file, `GeckoPluginHostTest.kt`.

This means the problem is not simply that no plugin integration exists. There is a partial implementation already in the fork.

## Confirmed functional gaps in the current Gecko host

The generated `__hcjBridge` in `GeckoPluginHost.bridgeStub()` currently contains no-op implementations for:

- `fetch`
- `notify`
- `panelOpen` and `panelClose`
- `share`
- `openExternal`
- `clearData`
- `exitApp`

The config and badge channels are handled, and panel messages can be forwarded, but the bridge stub does not yet provide feature parity with `PluginBridge` used by System WebView.

The current bridge also uses a shared literal token (`gecko`) in generated HCJ code rather than the per-session capability tokens used by `PluginBridge`. The prompt handlers shown in `GeckoPluginHost` do not currently validate the plugin's declared `PluginPermission` before applying config/badge/panel operations. This needs to be corrected before enabling privileged operations.

The userscript compatibility layer is intentionally minimal: storage methods return defaults/do nothing, and `GM_xmlhttpRequest` is a no-op. Chrome extensions are not run by this page-script path.

## Required implementation plan

1. Define a typed, versioned prompt protocol with request IDs and explicit success/error responses.
2. Validate plugin ID, per-session token, plugin existence, URL match, and declared permission for every operation.
3. Implement config read/write/remove with a page-side cache for synchronous reads and persisted writes through the native prompt channel.
4. Implement asynchronous fetch using an asynchronous Gecko-compatible channel; never pretend a synchronous prompt can provide network I/O.
5. Wire notify, badge, panel open/close/message, share, external navigation, clear-data, and exit-app to the existing PluginSession host callbacks, subject to permissions.
6. Either implement the required userscript GM APIs with equivalent permission checks or clearly mark unsupported APIs; do not silently report them as working.
7. Keep Chrome extensions out of the supported list until their Gecko WebExtension installation and popup/options lifecycle are implemented.
8. Add unit tests for authorization, URL matching, config persistence, malformed messages, permission denial, and each supported operation.
9. Run the Android CI workflow and test on-device with representative built-in HCJ plugins before claiming parity.

## Build notes

The repository already has `.github/workflows/android-ci.yml` with a `workflow_dispatch` packaging job. It builds debug/release variants and uploads APK artifacts. This audit does not change that workflow.

## Safety and release notes

Do not install a debug-signed APK over an existing release-signed app. Use it as a separate test installation if the application ID differs, or uninstall the existing app only after backing up its data and confirming the signing-key implications. A successful CI build is not proof that GeckoView plugins work; runtime tests are still required.
