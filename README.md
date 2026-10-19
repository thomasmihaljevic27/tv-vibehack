# tv-vibehack

**Your TV, minus the ads.**

Strip the bloatware from any Android TV or Google TV over Wi-Fi, and replace the ad-filled home
screen with a clean launcher that looks like an Apple TV. No root, no cable, nothing uninstalled,
and a one-line restore if you change your mind.

![Before: stock Google TV home with a sponsored ad. After: the tv-vibehack launcher](docs/stock-vs-new-side.jpg)

It started as an afternoon fixing my parents' Mi TV, which showed them an advert before they
could reach Netflix. The whole thing was done with Claude Code talking to the real TV, and the
prompt that did it is in this repo so you can do the same with yours.

---

## Table of contents

- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [What you get](#what-you-get)
- [Supported TVs](#supported-tvs)
- [Doing it by hand](#doing-it-by-hand)
- [The launcher](#the-launcher)
- [Fire TV](#fire-tv)
- [Troubleshooting](#troubleshooting)
- [Questions people ask](#questions-people-ask)
- [Contributing](#contributing)
- [Licence](#licence)

---

## How it works

Android TVs have the same debug bridge as Android phones. Once you switch on USB debugging in the
TV's developer options, the TV listens on your Wi-Fi and asks you once to allow your computer.
After that, everything is standard Android tooling:

1. **Back up** the list of installed packages and settings.
2. **Disable** the manufacturer's telemetry, ad services, content rows and preloaded junk with
   `pm disable-user`. Nothing is uninstalled, so a restore script can undo every step.
3. **Install** a small native launcher and make it the default home screen.

The bloat differs by brand, which is why the prompt asks your coding assistant to discover it
on your TV and ask you before touching anything you might use.

## Quick start

You need a computer on the same Wi-Fi as the TV and a coding assistant with shell access
(Claude Code, or anything similar).

**1. On the TV**
Settings → Device Preferences → About → press OK on **Build** seven times → go back →
Developer options → turn on **USB debugging**. Some TVs also show a Network or ADB debugging
toggle; turn it on too. Keep the TV switched on.

**2. On your computer**
Open your assistant, paste the prompt from [`PROMPT.md`](PROMPT.md), and fill in your TV model.

**3. Follow along**
Accept the "Allow USB debugging?" prompt when it appears on the TV, then answer the checklist
questions: which streaming apps you use, which four go in the dock, whether to keep the voice
assistant, Chromecast and firmware updates.

That's it. The assistant backs up, debloats, builds and installs the launcher, and checks every
step with screenshots pulled from the TV. On a 1 GB Mi TV the whole thing took one afternoon the
first time; with this repo as context, people report about fifteen minutes.

## What you get

A home screen that is yours again:

- **Pitch-black background** and a dock of your five favourite apps at the bottom, in a lighter
  rounded band, exactly where Apple TV puts it.
- **A top shelf** showing the focused app's name in large type, with an ambient glow in a colour
  taken from the app's own artwork.
- **The rest of your apps in a grid**, five across, that slides up when you press Down. With
  background art on, the grid sits on a glow in a colour taken from the painting.
- **One floating glass highlight** that springs from tile to tile instead of each tile drawing its
  own border. The focused tile lifts slightly and shows its name.
- **One frosted button in the top corner** that drops down Inputs (HDMI ports, AV and Live TV),
  Wi-Fi (showing the network you're on) and Settings.
- **A large thin clock and the date**, with everything set in
  [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex).
- **Rearrange, hide and uninstall apps on the TV**: hold OK on a tile to move it (the others slide
  out of the way), hide it or uninstall it. Hidden apps come back from **Home screen options** in
  the top menu, which also switches the background art and the navigation sound on and off.
- **Soft UI sounds** modelled on Sony's TV menus (synthesised, not sampled): an airy "boop" on
  every move, a short blip when you open an app, and a low bloom when you come back home. On by
  default.
- **Optional background art**: public-domain landscape paintings behind the dock, changing every
  ten minutes. Only works the museum tags as landscapes or seascapes, with nothing religious,
  military, nude or figure-centred. Off by default, since it's the only feature that goes online.
- **A matching Clock screensaver**, optional, that drifts around the screen so nothing burns in.

And a TV that stops working against you: no sponsored rows, no "Discover" nags, no analytics
phoning home, no apps you never asked for.

## Supported TVs

Anything running **Android TV** or **Google TV**: Xiaomi and Redmi, TCL, Hisense, Sony, OnePlus,
Realme, Nvidia Shield and most others.

Amazon Fire TV works too, with its own build of the launcher: see [Fire TV](#fire-tv).
Not supported: Samsung (Tizen) and LG (webOS), which run different systems.

Lists that people have tested and contributed live in [`examples/`](examples/):

| TV | OS |
|---|---|
| Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 |
| Xiaomi MiTV-AXSO0 | Android TV 9 |
| TCL 55" 4K Google TV, 2023 (G10) | Google TV 12 |
| SANYO ATV R2 | Android TV 9 |
| OnePlus TV (D00) | Google TV 14 |

Your TV not here? The prompt works regardless, it discovers the bloat itself. If you add your
list afterwards, the next person with your model skips that step. See
[Contributing](#contributing).

## Doing it by hand

Everything the assistant does, you can run yourself. The tools need Python 3 and nothing else.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP address

tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # run one shell command on the TV
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch   # several, one connection

tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # apply a debloat list
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # undo it and restore the stock launcher
```

| Tool | What it does |
|---|---|
| [`tools/tv.py`](tools/tv.py) | A pure-Python ADB client: shell, batch, push, pull. Works where the stock `adb` binary is blocked. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files such as APKs as base64 chunks and verifies the checksum. |
| [`tools/disable.sh`](tools/disable.sh) / [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. PowerShell versions sit next to them. |

### Windows

The PowerShell scripts work without WSL or Git Bash. With Android Studio installed nothing else
is needed: the install script uses Android Studio's bundled JDK and the default SDK location.

```powershell
python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 -TvHost 192.168.1.50
powershell -ExecutionPolicy Bypass -File tools\disable.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt
powershell -ExecutionPolicy Bypass -File tools\restore.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt
```

The install script stops before touching the stock launcher if the install or the home-screen
switch fails. Git Bash also works; the `.sh` scripts detect a Windows venv and disable MSYS path
rewriting themselves.

## The launcher

A small native Android TV app (`com.gathin.tvhome`, about 1.6 MB), written in plain Java with
AndroidX RecyclerView and nothing heavier. It is built for sets with 1 GB of RAM: no blur, no
video, no network, no background services.

**Configure** it in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
the dock order, apps to hide, your TV's Live TV package, and the number of columns.

**Build and install** with one command:

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

It needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

Without Homebrew, the script also finds a JDK 17 known to `/usr/libexec/java_home` and an SDK in
`~/Library/Android/sdk`, Android Studio's default. A release build (`./gradlew assembleTvRelease`)
is smaller and faster on 1 GB sets; sign it yourself before installing.

**Navigation** is deliberate: Up and Down move exactly one row, Up from the dock goes to the top
buttons, the ends of the button row don't fall through to tiles, and the last focused app is
remembered when you come back, even after the app list changes.

<details>
<summary><strong>Optional: the Art and Clock screensavers</strong></summary>

**Art** is an ambient mode like Google TV's: the background art full screen, a new painting every
90 seconds with a slow drift, its title for a few seconds, and a soft clock in the corner that
shifts a little every minute so nothing burns in. Choosing it is its own opt-in to the art, so it
shows paintings even with Background art off on the home screen; offline it cycles the cached ones.

**Clock** is a large thin clock and the date over an ambient glow, in the launcher's style. Every
minute the clock drifts to a new spot and the glow shifts colour. No network, no images.

The system runs either only while the TV is idle. Turn one on over ADB, or pick "Art" or "Clock" in
the TV's screensaver settings:

```bash
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ArtDream; settings put secure screensaver_enabled 1'
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1'
```

It starts after the TV's "screen off" idle time, in milliseconds: `settings put system
screen_off_timeout 300000` for five minutes. Neither is in the Fire TV build.
</details>

## Fire TV

Tested on a Fire TV Stick 4K Plus, Fire OS 8.1.7.4 (Android 11). The launcher has two product
flavours: `tv` (everything above, built by `build-and-install`) and `fire` (`launcher/app/src/fire`).
Fire OS needs the second one because it differs in four ways:

- **HOME can't be reassigned.** Fire OS ignores `set-home-activity`, Amazon's launcher
  (`com.amazon.tv.launcher`) must stay enabled, and the HOME key never reaches an accessibility key
  filter. The fire build adds `HomeGuard`, an accessibility service adapted from
  [dumbtv](https://github.com/humblemuzzu/dumbtv) (MIT). It watches only which window comes to the
  front, reads no screen content, and replaces Amazon's home with this one through an AlarmManager
  launch (which skips Android's 5 s app-switch delay) behind a black cover. On Android 11 that launch
  is only allowed once the cover is on screen. You see 0.5 to 2 s of black, never Amazon's home.
  Holding HOME still opens Fire's quick menu.
- **Settings and inputs.** `Settings.ACTION_SETTINGS` reopens Amazon's home, so the Settings entry
  opens `LEANBACK_SETTINGS` instead. A stick has no HDMI inputs, so the Inputs entry is gone.
- **Package names.** Prime Video is `com.amazon.firebat`, YouTube `com.amazon.firetv.youtube`. Many
  Amazon apps (Silk, the Appstore) declare only a phone launcher entry; the fire build lists them,
  the Appstore with its icon drawn as a banner. On Fire OS 8 the Appstore is a tab of Amazon's home,
  where HOME can't be caught, so `HomeGuard` leaves it open and **Back** brings you home.
- **No gear button, no Clock screensaver.** Fire OS does run a third-party screensaver, but it
  force-stops the screensaver's app when it ends, which would also stop `HomeGuard`.

Everything else is the same launcher as on Android TV: five columns, the tile menu (Move, Hide,
Uninstall), Home screen options, the optional background art with its glow behind the grid, and
the navigation sound. The fire dock lists four apps in `config.xml`; the fifth slot takes the next
app in the saved order, or add a fifth `dock_apps` entry.

Configure it in [`launcher/app/src/fire/res/values/config.xml`](launcher/app/src/fire/res/values/config.xml),
then build and install. The script backs up packages and settings to `backup\fire-tv` first and never
touches Amazon's launcher. `restore-fire.ps1` undoes everything (`-KeepApp` leaves the launcher installed).

```powershell
powershell -ExecutionPolicy Bypass -File launcher\install-fire.ps1 -TvHost 192.168.1.60
powershell -ExecutionPolicy Bypass -File launcher\restore-fire.ps1 -TvHost 192.168.1.60
```

If HOME ever lands on Amazon's home again, check that `HomeGuard` is still enabled
(`settings get secure enabled_accessibility_services`) and that
`settings get global alarm_manager_constants` still says `min_futurity=0`, then run the install
again.

## Troubleshooting

Things that bit us or contributors, so they don't bite you.

**Connecting**

- **macOS: `adb connect` says "No route to host"** although the port is open. Recent macOS treats
  the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs, Xiaomi among them. USB debugging alone opens
  network ADB on port 5555.
- **Windows PowerShell 5.1 adds a byte-order mark** to text piped into a program, which turned the
  first batch command into gibberish. `tv.py` now strips it, and the `.ps1` scripts pipe clean UTF-8.
- **Git Bash rewrites paths** like `/data/local/tmp` into Windows paths. The scripts set
  `MSYS_NO_PATHCONV=1` for you. Set `JAVA_HOME` and `ANDROID_HOME` before building there.

**Working on the TV**

- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does exactly that.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Screenshots come out empty** while Live TV or an HDMI input is on screen, seen on TCL. Press
  Home before `screencap`.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app.
- **The remote's gear button stops working** on Google TV once launcherx is disabled: it opens
  Google's dashboard, which lives in launcherx. The launcher's "Remote gear button" accessibility
  service takes over (menu on the home screen, Settings elsewhere); the install script turns it on.
  On TCL it also needs `cmd appops set com.gathin.tvhome APP_AUTO_START allow`, which the script runs.
- **No on-screen keyboard** after switching launchers, seen on Xiaomi: the default input method
  pointed at a Baidu keyboard that isn't installed. Fix:
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`
- **Third-party launchers** often charge for basics like a custom wallpaper. That's why this repo
  has its own.

## Questions people ask

**Is this safe? Can it brick the TV?**
No. Nothing is flashed, rooted or deleted. Packages are disabled for the user profile, which
lives in the data partition, not in the system image. The firmware is untouched. If something
goes wrong, `tools/restore.sh` puts everything back, and even without the tools you can re-enable
apps from the TV's own settings.

**What about firmware updates?**
Most lists disable the manufacturer's update service, so updates only arrive when you re-enable
it on purpose. If an update does land, the worst case is that a package comes back enabled and
you re-run the script.

**Won't the apps break as their APIs change?**
The streaming apps still update through the Play Store, which is left alone. The launcher
doesn't talk to them; it lists what's installed and launches it, using Android launcher intents
that have been stable for over a decade.

**How did it get access? Isn't that a security hole?**
Through the Android Debug Bridge, with one explicit "allow this computer" tap on the TV, the same
as developing an Android app. The TV only trusts the key you approved. You can switch USB
debugging off again afterwards and the launcher keeps working.

**Can I just download the launcher APK?**
Build it yourself with the one-line script above; it takes about a minute and you control what
goes on your TV. We don't publish prebuilt binaries.

## Contributing

The most useful contribution is your TV's debloat list, so the next person with that model
doesn't have to rediscover it. Launcher improvements and sharper prompt wording are welcome too.
See [CONTRIBUTING.md](CONTRIBUTING.md) for the format and the testing we ask for.

## Licence

MIT, see [`LICENSE`](LICENSE). The Google Sans Flex typeface is under the SIL Open Font License,
see `launcher/FONT-LICENSE-GOOGLE-SANS-FLEX-OFL.txt`; "Google Sans" is a trademark of Google LLC.
