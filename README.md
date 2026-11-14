# G7timing

A small Kotlin MultiPlatform app for iPhone and Android.
It is designed to help manage the auto-start of Dexcom G7/ONE+/Stelo CGM sensors.


> **Not a medical device.** G7timing doesn't read glucose, doesn't start a sensor session, and
> doesn't pair with or sign in to any sensor. Don't use it for treatment decisions. For questions
> about your sensor or therapy, ask your care team.

## Why postpone the auto-start of G7/ONE+/Stelo sensors?

The insertion-trauma of inserting a Dexcom sensor affects different people in different ways.
With the G6 the transmitter imposed a 2-hour warmup gap to try to let this settle.
The G7 shortened this to 30 minutes (although the 15-day version has moved it back to 1 hour).
But for a significant portion of the population, the values returned by both G6 and G7 sensors can
vary "randomly" for much longer periods.

In fact the author has found that they cannot trust Dexcom values until 24 hours have passed.
Even if the numbers seem to be behaving sensibly after a few hours, it's very likely to later
jump either significantly high or low
(in fact extended periods of LOW are likely, especially while trying to sleep).
But usually just before the 24 hours are up, the numbers will return to a level that closely
matches fingerpricks and other sensors. Usually without needing any calibration!


For some people the default warmup time may be enough.
And with the G7 we can insert a new sensor while still using the previous, and the new sensor
will auto-start then hopefully be settled by the time we connect to it.
That's where the "extra" 12 hours of the G7 comes from.
\
But if I have to wait 24 hours before I'm prepared to use the new sensor, my expensive G7 has
suddenly become a 9.5-day sensor.

But if we can postpone that start, the body should be able to get over much of the insertion
trauma before we start using up the sensor's allotted lifespan.

This was often done with G6 sensors (with an alternate transmitter clipped in to keep the sensor
safe until it was manually started).
Similar processed are also sometimes used with Libre and other sensors.
\
Colloquially this is referred to as **"pre-soaking"** the sensor.
Now it's possible with G7 too.

"Pre-soaking" a sensor refers to "soaking" it before the sensor starts.
\
Because they thought it wasn't possible to delay the start of a G7,
some people have been using the same term to apply to starting a G7 sensor before they were
going to use it.
\
That is different though: it's "pre-starting".

## Of course nothing's perfect

Unfortunately the auto-calibration of the G7 is usually not perfect after a significant delay
of the start. This matches the observed G6 behaviour too.
The longer the delay, the more likely it is that the sensor will report elevated numbers.
Sometimes by several mmol/L.

**However**, usually a single manual calibration is enough to bring the sensor into line.
It does take a couple of readings for the calibration to take hold though.
\
The sensor seems to then behave normally for the rest of its lifespan.

An area of ongoing personal experimentation is determining how long to pre-soak the sensor before
starting it, and then how soon the calibration can be applied.

But even though there may be a short period where the CGM data is a little high
that's soon corrected, and the ability to delay the G7 start can give us an extra day per sensor.
It's up to you as to whether you decide that's worth it for you.

## How it works with the G7: the mechanics

The G7 auto-start normally happens soon after the applicator is lifted off the sensor, and the sensor
will almost immediately enter its warmup phase and start its 10.5 (or 15.5) day lifespan.

However it seems that the G7 is actually waking up on a regular interval
(for mere seconds and only into a low-power mode) then
checking if the applicator's magnet can be sensed and if not, it goes back into deep sleep.

This seems to happen on some multiple of a 1-minute interval.
When it wakes up to check, there's a tiny bit of power also supplied to the Bluetooth radio,
and if we watch carefully we can see when that happens.
This is where the G7timing app comes in.
It lets us notice this, and keeps track of how many minutes/seconds have elapsed
since then.

If while the G7 is asleep we lift away the applicator then immediately place a small magnet onto
the sensor body, the G7 will stay asleep until that magnet is removed.

## How to use the app

There's a simple Start/Stop button to control whether the app is scanning for Bluetooth devices.

The applicator holding the sensor you're about to apply should be held against the back of the
phone to maximise the odds of detecting the occasional Bluetooth signal.

1. **Listen for G7s.** G7timing scans for G7 transmitters. These advertise names that start
   with `DXCM`, `DX01` or `DX02` (for example `DX0299`). G6 transmitters (`Dexcom…`) are
   ignored. Only transmitters at −99 dBm or stronger are contacted. A sensor in its applicator
   is very faint: one read −90 to −102 dBm while lying against the phone.
2. **Every G7 seen is listed**, with its status, when it was last seen, and its signal strength
   at that time. Every sighting and status change also goes into the event log.
3. **Connect once to sort it.** The first time a G7 is detected, G7timing connects and asks it to
   list its Bluetooth services. On Android it first clears the phone's own cache of that list,
   so the answer must come from the sensor itself. Android otherwise answers from the cache
   for devices it has connected to before: in one case the reply came 14 ms after connecting.
   - **It answers:** "Working sensor: left alone". It could be someone else's. G7timing
     disconnects straight away and never contacts it again in that session. It stays in
     the list, updated whenever the scan hears it.
   - **It accepts the connection but answers nothing** (the link then drops): "Accepts a
     connection, can't be checked". These cards go to the top of the list.
   - Too faint (below −99 dBm): listed, but never contacted.
   - If the phone can't clear its
     cache, the status is "Couldn't be sorted on this phone", and G7timing leaves it alone.
4. **Monitor.** Each time a transmitter that can't be checked wakes, G7timing connects and
   disconnects immediately. Its card shows:
   - its name and identifier
   - **time since it was last seen**, in minutes and seconds, counting up live
   - **when it was last seen**, and **its signal strength at that time**. If it was seen only by
     connecting, with no advertisement heard, the signal shows as "not measured".
   - the last connection time and the number of connections

The **STOP** button cancels everything and returns to the Start screen.
Nothing is saved, and each Start begins from scratch.

**G7timing can't tell which applicator a sensor belongs to.**
If more than one transmitter is showing as an option ("can't be checked"),
move the other applicators away and watch which stops being seen.

The screen stays on while a session runs. Monitoring only works in the foreground
(so if the phone powers off the monitoring is paused).

## Once you have identified it

While the app continues to scan, it shows a running count of the time since the sensor was last seen.
\
By waiting until it's been about however-many minutes but then 5 or so seconds since the last contact,
the sensor should remain asleep while the applicator is removed and the magnet is put in place.

The scanning can then be Stopped.

## Care around other people's sensors

Sorting a transmitter needs one connection, so G7timing may briefly connect once to any nearby
G7 at −99 dBm or stronger, including one someone is wearing. It then leaves it alone for the rest
of the session to avoid disrupting access to anyone's sensor.

Although no disruption to sensors was noticed during testing,
use the app away from other G7 users where you can.

## Keeping the magnet in place

A variety of magnets can be used. They don't need to be _super_-strong.
The magnet does need to remain in place though: when it's removed the sensor will soon auto-start.

Initial experiments worked with a magnet extracted from a used G7 applicator and held in a
block of Sugru so that it was in a similar position near that part of the G7.
The Sugru had been shaped around a used G7 sensor with the magnet embedded, let set, and then tidied
up with a sharp knife to let it smoothly and quickly settle into place onto the sensor.
\
There may be some 3D-printed covers produced with magnets place.

Another approach is to simply use an available magnet and stick it in place.
These ones have been working well:

![magnets](imgs/magnets.png)

After peeling off the adhesive layer (which is _too_ sticky) one of these sits neatly on top of the sensor.
\
To hold the magnet in place, a dressing with silicone adhesive
(such as _Opsite Flexifix Gentle_, or _Hypafix Gentle Touch Skin Sensitive_)
can be quite effective, as it can be gently removed (and even re-applied) without lifting the
G7 itself.

# Tech notes
### Catching a sleeping G7

A quiet G7 wakes only for a moment, at some multiple of 60 seconds after its last connection
attempt. One unworn sensor was seen on a 5-minute grid, always at the same second past the
minute. To catch these brief wake-ups, G7timing connects two ways at once and uses whichever
succeeds first:

- **A waiting connection**, handed to the phone's Bluetooth controller (Android `autoConnect`;
  every iOS connection request works this way). The controller connects on the transmitter's
  next advertisement, even one the scan didn't hear, but it listens at a low duty cycle.
- **An immediate connection**, started whenever the full-speed scan hears the transmitter.

On Android, the scan restarts every 4 minutes. A Moto g55 (Android 16) cut long-running scans to
a low duty cycle after 5 minutes, and brief advertisements were missed after that.

### Identifier on each platform

| Platform | Identifier shown | Notes |
|---|---|---|
| Android | Bluetooth MAC address | From `BluetoothDevice.address`. |
| iOS | CoreBluetooth peripheral UUID | **iOS never exposes MAC addresses.** The UUID is stable for one phone and transmitter pair, but differs on other phones. |

## What was learned on hardware

Tested with a Moto g55 (Android 16), and confirmed on an iPhone 15. The iPhone behaved the same,
including detecting two applicators at once, apart from showing iOS's own identifiers. Findings:

- An unworn G7 accepts a link-layer connection but then answers nothing, not even service
  discovery. The link drops after about 9–13 s (status 8, supervision timeout). xDrip+ behaves
  the same way: it shows the MAC address and then can't continue. Because of this, **a sensor's
  4-digit code can't be checked before insertion**.
- A working G7 lists its services within about 2.5 s, even at −90 dBm. That difference is how
  G7timing tells the two apart.
- Working G7s wake every 5 minutes. Unworn ones were heard less often and much more faintly.

- **A working sensor was not visibly affected.** The system paired with the working G7 used in
  testing showed no missed readings, across many test sessions that each connected to it once.
  That's one sensor and one system, so it's reassuring but not proof. G7timing still limits
  this to one connection per working sensor per session.

## Building

Requirements: JDK 17+, Android SDK with platform 37, Xcode 16+ (tested with Xcode 27.2).

```bash
./gradlew :shared:jvmTest              # controller tests
./gradlew :androidApp:assembleDebug    # androidApp/build/outputs/apk/debug/
open iosApp/G7timing.xcodeproj          # set your Development Team, then Run
```

The Xcode project calls `./gradlew :shared:embedAndSignAppleFrameworkForXcode` before compiling.

## App icon

The icon is a solid hourglass with amber sand and signal arcs on navy. It contains no Dexcom
marks or colours. The master artwork is `design/icon/g7timing-icon.svg`, plus a one-colour
version, `g7timing-icon-mono.svg`.

- **iOS:** `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`, rendered from the
  SVG. Xcode generates the smaller sizes.
- **Android:** an adaptive icon (`mipmap-anydpi-v26/ic_launcher*.xml`) with a navy background, a
  vector foreground (`drawable/ic_launcher_foreground.xml`) and a one-colour layer for Android 13+
  themed icons (`drawable/ic_launcher_monochrome.xml`). The minimum Android version (8.0) supports
  adaptive icons, so no PNG sizes are needed.

To change the icon, edit the SVG, copy the same path data into the two Android vector files, and
re-render the iOS PNG:

```bash
rsvg-convert -w 1024 -h 1024 design/icon/g7timing-icon.svg \
  -o iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
```

## Licence

Copyright 2026 David Burren

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this project except
in compliance with the License. You may obtain a copy of the License in [`LICENSE`](LICENSE) or at
<https://www.apache.org/licenses/LICENSE-2.0>.

Unless required by applicable law or agreed to in writing, software distributed under the License
is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the License for the specific language governing permissions and limitations under the
License.

Each source file carries an `SPDX-License-Identifier: Apache-2.0` header.
