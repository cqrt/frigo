# Frigo

Frigo puts your Frigate cameras on your phone. It talks to the Frigate server you
already run, so there is nothing to install on the server side.

## What it does

**Cameras.** A grid of your cameras, each tile updating about once a second. Tap
one and it fills the screen with a live view.

**Events.** What Frigate has recorded, newest first, with thumbnails. Filter by
camera and tap an event for the full snapshot.

**Settings.** Shows which server you are signed in to, and signs you out.

## Signing in

You enter the same address you use in a browser, your username, and your
password. If you reach Frigate through a reverse proxy, that is the proxy address,
port included. The app keeps the sign-in on the phone and renews it quietly, so
you only do this once.

## How live view works

The camera grid and the full screen player ask the server for a stream of still
frames over the same HTTPS connection as everything else (`/api/<camera>`).

That choice follows Frigate's own reasoning. Frigate deliberately serves its web
live view as MSE rather than WebRTC, because MSE runs over TCP and every frame
arrives, while WebRTC drops late frames on a poor connection. An Android app
cannot use MSE, so the closest equivalent is this JPEG stream: same TCP, same
guarantee, and it needs no extra ports or router changes.

The trade is bandwidth, since every frame is a whole picture. The player's menu
sets the height and frame rate, and the label in the corner tells you which you
are on.

WebRTC through go2rtc is planned as an option for low latency. It needs port 8555
forwarded to your Frigate machine, both TCP and UDP. Without that forward, WebRTC
connects and then shows nothing, which is why it is not the default.

## Building it

You need JDK 17 to 23, the Android SDK with platform 36, and nothing else.

```
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. For a release build, put
your keystore details in a gitignored `keystore.properties`, or set the
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment
variables:

```
./gradlew :app:assembleRelease
```

## Releasing

You cut a release on the GitHub website. Open Actions, pick Release, run the
workflow, and type the version as `0.2.0`. It builds the signed APK, tags the
commit, and puts the APK on the release page. Pushing a `v0.2.0` tag does the
same thing if you'd rather do that from the command line.

The version code comes from the version in the tag, so `0.2.1` becomes 201. A
phone with 0.2.1 already installed will take 0.3.0 as an update without you
uninstalling anything.

The key that signs the APK lives in the repository's secrets, so no key material
is ever committed. Backup: there's a copy of the key and its password at
`~/.frigo/frigo-release.jks`, and the password in the gitignored
`keystore.properties`. Lose that and no future build will install over this one.

## Where things live

- `data/FrigateApi.kt` — the Frigate endpoints this app uses, written against OkHttp.
- `data/FrigoRepository.kt` — tokens, renewal, and the calls the screens make.
- `data/MjpegReader.kt` — pulls JPEG frames out of the live stream.
- `ui/` — one folder per screen, Compose throughout.
