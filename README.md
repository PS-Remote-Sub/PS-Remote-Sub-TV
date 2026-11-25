# PSRS TV Overlay

แอป Android TV ที่ลอยซับไทยจาก [PS Remote Sub](https://github.com/PS-Remote-Sub/PS-Remote-Sub) บนคอม ให้ทับภาพเกมจากช่อง HDMI ของทีวี

**ติดตั้งจากแอปบนคอม:** เปิด PS Remote Sub → ตั้งค่า → แท็บ **ทีวี** → กด **ติดตั้งบนทีวี** แอปคอมแถม APK กับ adb มาให้ ติดตั้งแอปนี้ เปิดบริการการเข้าถึง และอัปเดตให้เองเมื่อมีเวอร์ชันใหม่ ก่อนกดต้องเปิดโหมดนักพัฒนากับ Network debugging บนทีวีครั้งเดียว (ขั้นตอนอยู่ในแท็บ **ทีวี** และใน README ของ PS Remote Sub หัวข้อ **ซับบนทีวี**) README นี้สำหรับผู้พัฒนา และเป็นทางสำรองถ้าติดตั้งจากแอปคอมไม่ได้

```
PS5 ──HDMI──► ทีวี (เล่นเกม)
 └─ Remote Play ─► PC: PS Remote Sub (OCR + แปล)
                      │ UDP broadcast พอร์ต 47610 (ประกาศตัวทุก 2 วินาที)
                      │ HTTP GET /tv/events (Server-Sent Events)
                      ▼
        แอปนี้บนทีวี: บริการการเข้าถึง วาดกล่องซับไทยทับภาพเกม (คลิกทะลุ)
```

แอปบนทีวีไม่ได้จับภาพจาก HDMI เพราะ Android ไม่ให้แอปทั่วไปอ่านภาพจากช่อง HDMI คอมเป็นฝ่ายอ่านภาพจาก Remote Play แล้วส่งมาแค่ข้อความกับตำแหน่งกล่อง (ต้องต่อจอยเข้า PC)

## แอปทำอะไร

- **บริการการเข้าถึง (Accessibility)** `OverlayA11yService` ถือหน้าต่างลอยเต็มจอ (`TYPE_ACCESSIBILITY_OVERLAY` กดไม่ได้ ไม่รับโฟกัส) ไม่ใช้ foreground service และไม่ใช้สิทธิ์ `SYSTEM_ALERT_WINDOW`
- **หาคอมเอง:** ฟัง UDP beacon ของ PS Remote Sub ที่พอร์ต **47610** (`{"app":"psrs","v":1,"id","name","port"}`) ไม่ต้องใส่ IP บนทีวี ถ้ามีหลายเครื่องจะเลือกเครื่องที่เคยเชื่อมล่าสุด ไม่งั้นเครื่องแรกที่ได้ยิน ถ้า beacon หายไปประมาณ 10 วินาทีจะกลับไปฟังใหม่
- **รับซับ:** เชื่อม `http://<IP คอม>:<พอร์ต>/tv/events` (SSE) ต่อใหม่เองถ้าหลุด คอมเป็นฝ่ายตัดสินว่าตอนนี้ต้องโชว์อะไร แอปแค่วาด: กล่องทึบทับตำแหน่งซับอังกฤษ (หรือแถบขอบล่าง) ตามค่า `config` ของคอม (รูปแบบ ขนาดตัวอักษร ความทึบ การปรับตำแหน่ง) ชื่อผู้พูดมีสีของตัวเอง
- ซับขึ้นเมื่อเชื่อมกับคอมและคอมเปิด **ส่งซับไปทีวี**; ปิดหรือหยุดชั่วคราวจากคอม (`Ctrl+Alt+Shift+P`) เหมือนกันทุกยี่ห้อ
- ไม่วาดอะไรถ้ายังไม่ได้เชื่อมคอม ยกเว้นตอนคอมสั่งปรับตำแหน่ง หลังเชื่อมจะขึ้นข้อความสถานะสั้น ๆ
- หน้าจอแอป (launcher) เป็นหน้าสถานะ: บริการเปิดอยู่ไหม, คอมที่เชื่อมอยู่, เวอร์ชันแอป

## Build

ต้องมี Android Studio ตัวติดตั้งให้ JDK, Android SDK และ adb มาครบ โปรเจกต์ใช้ Gradle 9.9.2 และ Android Gradle Plugin 9.5.2 (คอมไพล์ Kotlin ในตัว), compileSdk 37

จาก Android Studio: เปิดโฟลเดอร์นี้ รอ Gradle sync เสร็จ แล้วกด **Build → Build App Bundle(s) / APK(s) → Build APK(s)**

หรือจาก PowerShell โดยใช้ JDK ที่มากับ Android Studio:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleDebug
```

ได้ไฟล์ `app\build\outputs\apk\debug\app-debug.apk`

## ติดตั้งบนทีวีด้วยมือ (ทางสำรอง)

ใช้เมื่อปุ่ม **ติดตั้งบนทีวี** ในแอปคอมใช้ไม่ได้ หรือตอนพัฒนาแอป

1. เปิดโหมดนักพัฒนาบนทีวี: **ตั้งค่า → ระบบ → เกี่ยวกับ → บิลด์ระบบปฏิบัติการ Android TV** กด OK 7 ครั้ง
   (บางรุ่นอยู่ที่ **ตั้งค่า → การตั้งค่าอุปกรณ์ → เกี่ยวกับ → บิลด์**)
2. ใน **ตัวเลือกสำหรับนักพัฒนา** เปิด **การแก้ไขข้อบกพร่อง USB / ADB** และถ้ามี เปิด **การแก้ไขข้อบกพร่องผ่านเครือข่าย** ด้วย
3. บนคอม (`adb` อยู่ใน `%LOCALAPPDATA%\Android\Sdk\platform-tools`):

```powershell
adb connect <IP ของทีวี>:5555
```

   ทีวีจะถามว่าอนุญาตคอมเครื่องนี้ไหม ให้กดอนุญาต

   ติดตั้งไฟล์ release ที่เซ็นด้วยกุญแจ release เท่านั้น: `tv\psrs-tv-overlay.apk` ในโฟลเดอร์ของแอปคอม หรือ build เองด้วย `assembleRelease` (ดูหัวข้อ **Signing**):

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

   **อย่าติดตั้ง `app-debug.apk` บนทีวีที่จะใช้กับแอปคอม** ตัว debug เซ็นด้วยกุญแจ debug ทำให้การติดตั้งและอัปเดตอัตโนมัติจากแอปคอมครั้งต่อไปล้มเหลวเพราะลายเซ็นไม่ตรงกัน (signature mismatch) ถ้าเคยติดตั้งตัว debug ไว้แล้ว ให้ถอนก่อน (`adb uninstall th.psrs.tvoverlay`) แล้วติดตั้งตัว release และเปิดบริการการเข้าถึงใหม่ (ขั้นที่ 4)

4. เปิดบริการการเข้าถึง (Accessibility) ของแอป **ต้องเพิ่มต่อท้ายรายการเดิม** ห้ามเขียนทับ ไม่งั้นบริการอื่นที่เปิดอยู่จะหลุด (ปุ่ม **ติดตั้งบนทีวี** ในแอปคอมทำขั้นตอนนี้ให้อัตโนมัติ):

   บน Android 13 ขึ้นไป ปลด restricted settings ก่อน ไม่งั้น Google TV บางรุ่น (TCL, Sony) จะปิดบริการกลับเอง (Android 12 ลงไปจะขึ้น error ว่าไม่รู้จัก op นี้ ข้ามไปได้):

```powershell
adb shell appops set th.psrs.tvoverlay ACCESS_RESTRICTED_SETTINGS allow
```


```powershell
adb shell settings get secure enabled_accessibility_services
```

   - ถ้าได้ `null` หรือค่าว่าง ใส่ของเราอย่างเดียว:

```powershell
adb shell settings put secure enabled_accessibility_services th.psrs.tvoverlay/.OverlayA11yService
```

   - ถ้ามีรายการเดิมอยู่ ให้ต่อท้ายคั่นด้วย `:` เช่น รายการเดิมคือ `com.a/.A`:

```powershell
adb shell settings put secure enabled_accessibility_services "com.a/.A:th.psrs.tvoverlay/.OverlayA11yService"
```

   รอสักครู่ (2-3 วินาที) แล้วอ่านค่ากลับมาเช็กว่ามีชื่อบริการของแอปอยู่ในรายการ ถ้าถูกระบบตัดทิ้งไป ให้สั่ง put ซ้ำอีกครั้ง:

```powershell
adb shell settings get secure enabled_accessibility_services
```

   สุดท้าย ถ้า `adb shell settings get secure accessibility_enabled` ไม่ใช่ `1` ให้เปิด:

```powershell
adb shell settings put secure accessibility_enabled 1
```

## ทดสอบ

1. เปิดแอป **PSRS TV Overlay** จากหน้าแอปของทีวี หน้าจอต้องขึ้นว่าบริการเปิดอยู่ พร้อมเลขเวอร์ชัน และชื่อคอมเมื่อเชื่อมแล้ว
2. บนคอม เปิด PS Remote Sub แล้วเปิด **ส่งซับไปทีวี** ที่แท็บ **ทีวี** ของตั้งค่า (ตั้ง Wi-Fi เป็น Private และอนุญาต firewall เหมือนตอนใช้มือถือ ทีวีต้องอยู่ในวง LAN เดียวกัน) แอปบนทีวีจะหาคอมเอง
3. สลับไปช่อง HDMI ของ PS5 แล้วเล่น ซับไทยจะขึ้นทับซับอังกฤษ ปิดหรือหยุดซับชั่วคราวจากคอม (`Ctrl+Alt+Shift+P`) ซับต้องหายทันที
4. ปรับตำแหน่งให้ตรงภาพได้ที่ปุ่ม **ปรับตำแหน่งบนทีวี** ในแอปคอม

## ข้อมูลแก้ปัญหา

แอปบนทีวีเก็บบันทึกการทำงานไว้เองเพื่อไว้ไล่ปัญหา ไม่เก็บข้อความซับหรือคำแปลเลย และ PcId ของคอมจะเก็บเป็นค่าแฮช 8 ตัวอักษรเท่านั้น ไม่เก็บชื่อเครื่อง

- `diag.log` และ `diag.1.log` ในโฟลเดอร์ `files` ของแอป (หมุนไฟล์ทุก 256 KB)
- `crash.txt` บันทึกข้อผิดพลาดที่ทำให้แอปปิดครั้งล่าสุด

อ่านผ่าน adb ได้ 3 ที่อยู่ (ต้องใช้ `adb shell` เพราะผู้ให้ข้อมูลถูกป้องกันด้วยสิทธิ์ `DUMP` แอปอื่นบนทีวีอ่านไม่ได้):

```powershell
adb shell content read --uri content://th.psrs.tvoverlay.diag/state
adb shell content read --uri content://th.psrs.tvoverlay.diag/log
adb shell content read --uri content://th.psrs.tvoverlay.diag/crash
```

ทางสำรองถ้า `content read` ใช้ไม่ได้ (ได้สถานะ แถว `---` แล้วตามด้วยบันทึก แต่ต้องเปิดบริการการเข้าถึงอยู่):

```powershell
adb shell dumpsys activity service th.psrs.tvoverlay/.OverlayA11yService
```

## Signing

ไฟล์ release เซ็นด้วยคีย์ของเราเอง:

- คีย์อยู่ที่ `%USERPROFILE%\.psrs\psrs-tv-release.jks` (alias `psrs-tv`, RSA 4096) รหัสผ่านอยู่ใน `keystore.properties` ที่รากของโปรเจกต์ (ไม่ถูก commit เพราะอยู่ใน `.gitignore` พร้อม `*.jks`)
- Gradle อ่าน `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) แต่ละค่าที่ไม่มีในไฟล์จะไปอ่านจาก env ตัวที่ตรงกัน `PSRS_TV_KEYSTORE`, `PSRS_TV_KEYSTORE_PASSWORD`, `PSRS_TV_KEY_ALIAS`, `PSRS_TV_KEY_PASSWORD` ถ้าครบทั้ง 4 ค่าไม่ได้จากทั้งสองแหล่ง release จะไม่ถูกเซ็น (debug build ไม่กระทบ)
- **สำรองไฟล์ `.jks` และรหัสผ่านไว้ที่ปลอดภัยนอกเครื่องนี้** ถ้าคีย์หาย จะอัปเดตแอปทับตัวเดิมที่ติดตั้งในทีวีไม่ได้ ต้องถอนการติดตั้งแล้วติดตั้งใหม่ (และเปิดบริการการเข้าถึงใหม่)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<version>\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

## Release

แอปคอมโหลด APK จาก GitHub release ล่าสุดของ repo นี้ (asset ชื่อ `psrs-tv-overlay-<versionCode>.apk`) แล้วติดตั้ง/อัปเดตบนทีวีเอง ถ้า versionCode ใหม่กว่าตัวที่แถมมากับแอปคอม ถ้าโหลดไม่ได้จะใช้ตัวที่แถมไว้แทน repo ต้องเป็น public เพราะแอปคอมโหลดโดยไม่มี token

ออก release ใหม่:

1. เพิ่ม `versionCode` และเปลี่ยน `versionName` ใน `app/build.gradle.kts` แล้ว merge เข้า `main`
2. push tag `v<versionName>` เช่น `git tag v1.1 && git push origin v1.1`
3. workflow `.github/workflows/release.yml` จะรันเทสต์ build release เซ็นด้วยกุญแจ release ตรวจว่าลายเซ็นตรงกับ cert `b55d9e19…0204` และ tag ตรงกับ versionName แล้วสร้าง release พร้อมไฟล์ `psrs-tv-overlay-<versionCode>.apk`

ตั้ง secret ของ repo ครั้งเดียว (Settings → Secrets and variables → Actions):

| Secret | ค่า |
|---|---|
| `PSRS_TV_KEYSTORE_BASE64` | base64 ของ `psrs-tv-release.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.psrs\psrs-tv-release.jks"))`) |
| `PSRS_TV_KEYSTORE_PASSWORD` | `storePassword` ใน `keystore.properties` |
| `PSRS_TV_KEY_ALIAS` | `psrs-tv` |
| `PSRS_TV_KEY_PASSWORD` | `keyPassword` ใน `keystore.properties` |

## ไฟล์

- `OverlayService.kt`: foreground service ถือหน้าต่างลอย (`TYPE_APPLICATION_OVERLAY`, กดไม่ได้, ไม่รับโฟกัส) และวาดซับ
- `SseClient.kt`: อ่าน `GET /events` ของคอม ต่อใหม่ทุก 3 วินาทีถ้าหลุด
- `SubtitleState.kt`: เก็บบรรทัดปัจจุบันของแต่ละโซนจาก event `subtitle` / `refined` / `clear` แบบเดียวกับหน้าเว็บมือถือ (บรรทัดที่ยังรอ AI จะยังไม่ขึ้นจนกว่าจะได้คำแปล)
- `MainActivity.kt`: หน้าตั้งค่าบนทีวี

## สแกน secret

repo นี้ใช้ [gitleaks](https://github.com/gitleaks/gitleaks) กัน API key และ secret อื่นไม่ให้หลุดเข้า git

- **ในเครื่อง:** ติดตั้ง [pre-commit](https://pre-commit.com) (`brew install pre-commit` หรือ `pip install pre-commit`) แล้วรัน `pre-commit install` ครั้งเดียวต่อการ clone หลังจากนั้นทุก commit จะถูกสแกนก่อน ถ้าไม่ได้ติดตั้ง hook จะไม่มีอะไรตรวจในเครื่องเลย
- **CI:** workflow `gitleaks` สแกน history ทั้งหมดทุกครั้งที่ push และเปิด PR จึงจับได้แม้จะ commit ด้วย `--no-verify` หรือแก้ผ่านหน้าเว็บ
- **ถ้าแจ้งเตือนผิด:** ใส่ comment `gitleaks:allow` ท้ายบรรทัดนั้น หรือเพิ่ม path/ค่าไว้ใต้ `[allowlist]` ใน `.gitleaks.toml`
- **ถ้าสิ่งที่เจออยู่ใน history ที่ push ไปแล้ว** (CI แดงทุก branch): `gitleaks:allow` ใช้ได้เฉพาะเมื่ออยู่ใน commit เดียวกับบรรทัดนั้น ให้คัดลอกบรรทัด `Fingerprint:` จาก log ของ CI ไปใส่ในไฟล์ `.gitleaksignore` ที่ root ของ repo บรรทัดละหนึ่งรายการ (ถ้าเป็น secret จริง ให้ rotate ก่อน)
- **ถ้า secret จริงหลุดเข้า commit ไปแล้ว:** ให้ rotate key นั้นทันที การลบออกใน commit ถัดไปไม่พอ เพราะยังค้างอยู่ใน history
