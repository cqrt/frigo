# Frigo

What changed in each release, newest first. The release workflow copies the
section for the version it is building onto the release page, which is also what
the app shows under **What's new**. A version with no section here is not
released, so the notes are never left to memory.

One line per change, in a sentence you'd say out loud. Anything you'd have to
explain, leave out.

## 0.10.31

- Only an admin can add a server or switch between them now. Everyone else sees the servers the phone holds, with the one they're on ticked.
- Alerts and detections, AI descriptions and System sit under Servers, in a card called Frigate, which only an admin sees.

## 0.10.30

- Alerts for a person Frigate has recognised arrive again. Its review says `person-verified` once the face is matched, and the app was reading that as a different object from the `person` on the camera's alert list, so those alerts never buzzed at all.
- On People to leave out, unticking the last name and pressing Done now saves. It means the same as Everyone: nobody is left out.

## 0.10.29

- People to leave out waits for Frigate's description before an alert goes, because the name is in it. While the list has anyone on it, alerts arrive about twenty seconds later.
- A review that never gets a description is decided after a minute, so no alert is lost. With nobody on the list, alerts are instant as before.
- A server that doesn't describe its alerts can't use the list, and the page says so.

## 0.10.28

- Only these objects sets one camera at a time: a camera opens with every object it alerts on ticked, and you untick what you don't want. The All cameras scope and the Everything row are gone.
- Leaving every object ticked keeps that camera unfiltered, so an object your server starts alerting on later is included.
- Alerts and detections sets one camera at a time too, the way Frigate's own settings do.

## 0.10.27

- Only these objects no longer offers a camera whose Alerts switch is off in Alerts and detections, since that camera never makes one.

## 0.10.26

- Only these objects keeps a list per camera: All cameras sets what every camera follows, and a camera of its own keeps what you tick there.
- A camera now lists only the objects it alerts on, so one you've turned off for it no longer shows.

## 0.10.25

- Alert labels and Detection labels wear the same cards, rows and filled search box as the rest of Settings.
- Tapping anywhere on a label's row ticks it, and the tracked labels keep their mark beside the checkbox.

## 0.10.24

- Alerts and detections, and Only these objects, only offer what your server can actually report: the objects each camera tracks, and the sounds it listens for.
- A label that can't happen is no longer offered. One already chosen stays listed, so you can take it off.

## 0.10.23

- Settings, Notifications has People to leave out: a name Frigate recognises, and this phone stays quiet about it.
- With someone on that list, alerts arrive up to four seconds later, so the name has landed. With nobody on it, nothing waits.
- The list is this phone's own. Your server and its other phones don't change.

## 0.10.22

- Only these objects wears the same cards, rows and filled search box as the rest of Settings.
- A camera chip on that page narrows the list to one camera's objects. There is still one list, and a tick means the same on every camera.

## 0.10.21

- Settings has Only these objects: a list of your own that narrows what buzzes this phone. An alert about anything that isn't on it stays quiet.
- The list is this phone's own, so it works when you aren't an admin. Your server and its other phones don't change.
- Alerts and detections and AI descriptions moved under the Server card in Settings, which an admin sees. Review's tune icon is an admin's too.

## 0.10.19

- A camera can be turned off on this phone alone. It leaves the Cameras tab, the strip under a picture and the chips on Review and Explore, and it stops buzzing. Your server and its other phones don't change.
- Settings, Cameras is now the list of every camera, with a switch on each. Hold a row and drag it to put the cameras in your order.
- A camera's own page can give it a name only this phone uses.

## 0.10.20

- Closing Frigo from the recent apps list now asks for your fingerprint the next time you open it. Before, only a force close did.

## 0.10.18

- Frigo can ask for your fingerprint, or your phone's PIN, before it opens. It sits under Privacy in Settings, and the walkthrough offers it too.
- It asks each time the app starts, not each time you come back to it.
- While it's on, the app is a blank card in the recent apps list, and a screenshot comes out blank.

## 0.10.17

- Frigo speaks English, Spanish, French, German and Portuguese. Language sits under Appearance in Settings, and Follow the phone is what it does until you pick one.
- The whole app changes with it: the screens, the walkthrough, the days and months under a camera, and the words when something fails.
- Your phone's own per-app language list has Frigo in it too, on Android 13 and later.

## 0.10.16

- The Settings icon in the bar carries a small red dot while a newer Frigo is waiting.
- Frigo asks GitHub when it opens and when you come back after an hour, so the dot is right before you go looking.

## 0.10.15

- Settings has a System page for an admin: what the server is running, how hard the box is working, each camera's health, the disks, and Frigate's own processes.
- The same page restarts Frigate, after asking first.

## 0.10.14

- Quiet times in Settings: pick two times and the cameras to leave out, and nothing from them buzzes in between. The review is still in Review.
- Quiet for an hour, for when you want silence now without changing the times.

## 0.10.13

- The About card gets straight to the version and the author.

## 0.10.12

- Settings has an AI descriptions page: it says whether your server can describe what it sees, turns it on and off, and sets up an Ollama model without leaving the app.
- The tour ends with what an alert looks like once your server has described it, shown only when your server is set up for it.
- One buzz per alert, always. The switch between the two ways is gone.

## 0.10.11

- An opened alert shows the picture and the whole sentence, instead of one or the other.

## 0.10.10

- An opened alert shows its sentence in full, with the picture as a thumbnail beside it, instead of cutting the sentence to one line.

## 0.10.9

- An alert reads the same open or closed: the sentence sits under the title, and the camera name stops repeating what is already up beside the time.
- The quick sentence uses the name your server has put on the person, even when face recognition only matches a second after the buzz.

## 0.10.8

- An alert picks up a short AI sentence about four seconds after it buzzes, from three pictures a second apart, instead of waiting half a minute for the review to end.
- Your server's own description still takes over when the review finishes.

## 0.10.7

- An alert fills in with your server's AI description a few seconds after it buzzes, in the same notification.

## 0.10.6

- Turning cameras on one at a time on your server is enough for the app to buzz, even with the All Cameras switch off.

## 0.10.5

- Settings, Alerts and detections, and a camera's page open with the server away, showing the settings your phone last read.
- More than one server, each with its own sign-in, alerts switch and camera layout, switched from Settings.
- Settings has an Add a server row.

## 0.10.4

- Alerts follow your server's own settings now, instead of a list kept on the phone.

## 0.10.3

- Thumbnails on the Review, Explore and Clips pages have rounded corners.
- The Clips page says Saved clips.

## 0.10.2

- The picture on the walkthrough's live page is a camera's shape now, instead of a letterbox.

## 0.10.1

- The first-run pages show drawn pictures now, instead of grey boxes.
- The walkthrough has two camera pages: the grid, then one camera open with the day under it.
- The Clips page says clips, not stretches.
- Signing in looks like the rest of the app, and the password has an eye.

## 0.10.0

- The first run walks you through the app: your server, alerts, battery, and a page for each tab.
- The alerts page starts with nothing ticked, and says what to do if Android turned the notifications down.
- Battery settings now opens Frigo's own page in Android's settings.
- Settings has Show the welcome again, under About.

## 0.9.9

- Sign out is a small button in the Server card now, not a big one at the bottom of Settings.

## 0.9.8

- The home page has two layouts now: a grid, and one camera to a row.

## 0.9.7

- Save asks where the stretch should go: a file on your phone, or kept on the server.
- Frigate cuts out the ones you keep itself, and holds them after its clean-up has taken the recordings.
- Clips is a fifth tab. It lists what you've kept, newest first, with the picture of each.
- A kept clip plays, saves to the phone, takes another name, or goes for good.
- A stretch Frigate is still cutting out says so, and fills itself in when it's done.

## 0.9.6

- The calendar puts a dot under each day that still has something to look at.
- A week in the calendar starts on the day your phone starts it.

## 0.9.5

- Review says how many alerts and how many detections are left.
- One button marks everything on the tab as reviewed, without playing any of it.
- If that was too much, put them back.
- All time goes back through everything again, not just the last day.

## 0.9.4

- A recording plays from where you leave the bar again.

## 0.9.3

- Settings is laid out in groups, and each camera says what it has switched off.
- Settings ends with an About section: the version, who it's by, and the copyright.
- The pages behind Settings wear the same cards: the camera's switches, Alerts and detections, Review and Explore.
- A camera's eight switches are grouped into detection, recording, live view and recognition.
- Review and Explore narrow from one row of chips, so the list gets the rest of the screen.

## 0.9.1

- Review narrows by camera, by label and by day.
- The labels offered are the ones turned on for that tab.

## 0.9.0

- Settings lists your cameras, and each one has its own page with eight switches: object detection, motion detection, recording, snapshots, audio detection, birdseye, face recognition and license plate recognition.
- Recording asks before it stops, since nothing new is saved after that.
- The labels on the Review settings page are Frigate's own, eleven of them, rather than every object the model can name.
- That page is called Alerts & Detections, and each card says what it sets instead of a note at the bottom.

## 0.8.2

- Review has a settings page, so you can choose which objects make an alert or a detection, for all cameras or just one.
- Leave the app and come back, and the picture is live again rather than from minutes ago.
- A run of recordings draws as one bar, instead of hundreds of hairlines.
