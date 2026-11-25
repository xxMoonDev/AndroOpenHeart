# AndroOpenHeart

An SSH server app for Android. Log in to your phone from a PC with any SSH client and get a shell, run commands, or move files through pipes. It runs as root on rooted phones, or as Android's ADB shell user through [Shizuku](https://github.com/RikkaApps/Shizuku) on phones without root.

It's the device side of [AndroUnderTheHood](https://github.com/xxMoonDev/AndroUnderTheHood) and [scrcpy-ssh](https://github.com/xxMoonDev/scrcpy-ssh), but works with plain `ssh` too.

> The app was called **RootSSH** before version 1.3. The package name is still `dev.rootssh`, and the environment variables still start with `ROOTSSH_`, so existing setups and clients keep working.

## Features

- **SSH server** (Apache MINA SSHD) on a port you choose (default 2022). It runs as a foreground service and can start at boot.
- **Login with keys or a password.** Paste OpenSSH public keys (ed25519, ECDSA, RSA) or set a password of at least 8 characters. Failed passwords are slowed down.
- **Interactive shells** get a real terminal (pty), so `top`, `vi` and Ctrl+C work. **Commands and pipes** (`ssh phone cmd`, `tar … | ssh phone …`) use plain pipes, so binary data passes through untouched.
- **Three ways to run sessions:**

  | Mode | Runs as | Needs |
  |---|---|---|
  | Root | uid 0 via `su` | A rooted phone (Magisk, KernelSU, APatch) |
  | Shizuku | ADB shell user (uid 2000) | [Shizuku](https://github.com/RikkaApps/Shizuku) running, no root |
  | App | The app's own user | Nothing (limited access) |

  Each session gets `ROOTSSH_MODE` (`root`, `shizuku` or `app`) and `ROOTSSH_PERMS` (special permissions the app holds) in its environment. A client can ask for a full root session by sending the environment variable `ROOTSSH_ROOT=1` (for example `ssh -o SetEnv=ROOTSSH_ROOT=1 …`).
- **Setup over ADB without root.** A broadcast receiver that only the ADB shell can use adds your key, sets the port and starts the server:

  ```
  adb shell am broadcast -a dev.rootssh.action.SETUP -n dev.rootssh/.AdbSetupReceiver \
      --es add_key "ssh-ed25519 AAAA… me@pc" --ei port 2222 --ez use_root false --ez start true
  ```

  Other options are `username`, `use_root`, `use_shizuku` and `start_on_boot`. The reply includes the host key and fingerprint, so the PC can check it on the first login.

## Using it

1. Install the APK from the [Releases](https://github.com/xxMoonDev/AndroOpenHeart/releases) page and open the app.
2. Set the port and user name, paste your PC's public key (for example `~/.ssh/id_ed25519.pub`) or set a password, and choose root or Shizuku.
3. Press **Start server**. The app shows its IP addresses and the host key fingerprint.
4. From the PC: `ssh -p 2022 root@PHONE-IP`. Check that the fingerprint matches the one in the app.

Some extra permissions (logs, usage stats, secure settings) can only be granted over ADB:

```
adb shell pm grant dev.rootssh android.permission.READ_LOGS
adb shell pm grant dev.rootssh android.permission.DUMP
adb shell pm grant dev.rootssh android.permission.WRITE_SECURE_SETTINGS
adb shell appops set dev.rootssh GET_USAGE_STATS allow
```

There's no SFTP subsystem and no port forwarding. Tools that need file transfer run `cat`, `tar` or similar over a session instead, as AndroUnderTheHood does.

## Security

- Anyone who can log in has a root shell (root mode) or the shell user's rights (Shizuku mode). Use keys rather than a password, and don't open the port to the internet; use your LAN, a VPN or a USB port forward (`adb forward tcp:2222 tcp:2222`).
- The host key is generated on the phone on first start (`ssh_host_ec_key` in the app's private folder). Reinstalling the app creates a new one, so clients warn that the key changed.

## Building

Needs the Android SDK, NDK and CMake 3.22.1 (the `pty` helper is a small C library). From a shell:

```
./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`. Without signing details, release builds use the debug key. To sign with your own key, create `keystore.properties` next to `settings.gradle.kts` (it's in `.gitignore`; never commit it or the keystore):

```
storeFile=C:/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

APKs signed with different keys can't update each other. Uninstall the old app first.

## Credits

AndroOpenHeart is built on:

- [Apache MINA SSHD](https://github.com/apache/mina-sshd) (Apache-2.0): the SSH server.
- [EdDSA-Java](https://github.com/str4d/ed25519-java) by str4d (CC0-1.0): ed25519 keys.
- [SLF4J](https://www.slf4j.org/) (MIT): logging interface (no-op binding).
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API) by RikkaApps (MIT), and the [Shizuku](https://github.com/RikkaApps/Shizuku) app (Apache-2.0): running as the shell user without root.
- [AndroidX](https://developer.android.com/jetpack/androidx) and [Material Components for Android](https://github.com/material-components/material-components-android) (Apache-2.0).

## License

Copyright 2026 xxMoonDev. Licensed under the [Apache License 2.0](LICENSE).
