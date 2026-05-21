# PlanSync Android — Setup Guide

## What this is

A minimal Android app that loads `https://plansyncapk.vercel.app` in a WebView.
GitHub Actions builds and signs the APK automatically on every push to `main`.

---

## One-time setup

### 1. Generate a signing keystore (do this once, on your machine)

```bash
keytool -genkey -v \
  -keystore plansync.jks \
  -alias plansync \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

You'll be prompted for a keystore password, your name/org, and a key password.
**Save these passwords — you need them forever.**

### 2. Encode the keystore as base64

```bash
# macOS / Linux
base64 -i plansync.jks | pbcopy   # copies to clipboard on macOS
base64 -i plansync.jks            # print and copy manually on Linux
```

### 3. Add GitHub Secrets

Go to your repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The base64 string from step 2 |
| `KEYSTORE_PASSWORD` | Password you set in step 1 |
| `KEY_ALIAS` | `plansync` (or whatever alias you used) |
| `KEY_PASSWORD` | Key password from step 1 |

### 4. Push to GitHub

```bash
git add .
git commit -m "Add Android WebView app"
git push origin main
```

---

## Getting the APK

After the GitHub Actions run completes (~3 minutes):

- **GitHub Releases** — go to your repo → Releases → download `PlanSync-vN.apk`
- **Actions tab** — click the latest run → Artifacts → download `PlanSync-APK`

You can also trigger a build manually: Actions → Build APK → Run workflow.

---

## Installing on your phone

1. On your Android device, go to **Settings → Apps → Special app access → Install unknown apps**
2. Allow your browser or file manager to install APKs
3. Download and open the APK

---

## App features

- Loads `https://plansyncapk.vercel.app` full-screen
- Pull-to-refresh
- Back button navigates browser history
- Offline screen with retry button
- Firebase Auth works (localStorage + cookies persisted)
- External links open in the system browser
- Deep links (`https://plansyncapk.vercel.app/...`) open in the app
