# The doorbell on the television

Someone presses the bell, and the camera at the door fills the screen for half a
minute. Then the television goes back to what it was showing.

Nothing in Frigate knows about a doorbell. The press lives in Home Assistant, so
Home Assistant is the one that rings the television.

## The address

On the television, **Settings**, then **Doorbell**. It shows an address like this:

```
http://192.168.0.139:8972/ring/13e970bbee
```

The last part is a key the television made for itself once and keeps, so another
automation on the same Wi-Fi can't ring it. Give the television a fixed address in
your router. The one in the automation stops working the day the router hands out
a different one.

## In Home Assistant

Add a REST command, in `configuration.yaml`:

```yaml
rest_command:
  frigo_bell:
    url: "http://192.168.0.139:8972/ring/13e970bbee"
    method: post
```

Then ring it from the doorbell. This is the sensor Tapo Cameras Control makes for
a D235:

```yaml
automation:
  - alias: Doorbell rings the television
    triggers:
      - trigger: state
        entity_id: binary_sensor.front_door_doorbell
        to: "on"
    actions:
      - action: rest_command.frigo_bell
```

Restart Home Assistant, or reload the REST commands and automations, and press the
bell to try it.

## Which camera

The door camera is the one Settings calls the Default camera. To bring up another
one, add it to the address:

```
http://192.168.0.139:8972/ring/13e970bbee?camera=north_cam
```

## What it doesn't do

The address is only open while Frigo TV is running. A television that has been
force-closed, or one that has just been turned on and has not had the app opened
yet, has no door for the bell.

Android sometimes refuses to bring an app to the front from the background. When
that happens the bell still counts: the picture is up the next time you look at
the screen. A television left on the cameras, which is the usual case, shows the
bell straight away.
