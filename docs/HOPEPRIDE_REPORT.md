# HopePride SAMP — Laporan Implementasi

Build tervalidasi: **CI run 42476428555** (`main`, commit `223a645`), status **success**.
APK debug: `release/HopePride-SAMP-Android.apk` — **75.774.003 byte** (≈72,3 MB),
SHA-256 `9882439ea608ab08fa7d12469875ec817588a7c1b24ca63185599c7d72b8e2b6`.

---

## 1. Root cause crash `GetTexture`

Fakta dari log: `fault 0x116d91a38`, `x1 = 0`, `x2 = 0x6a006f73`, `pc 0x286790`,
`lr 0x286780`. Kedua register x1 dan x2 adalah **argumen**, bukan pointer objek:
`x1` adalah nama texture (`const char*`) yang null, `x2` berisi byte `s\0o\0j\0`
— jelas potongan string UTF-16, bukan nama texture ASCII.

`pc` hanya 16 byte setelah entry thunk `+0x286718`, jadi fault terjadi di instruksi
awal fungsi, sebelum ada lookup dictionary. Artinya:

* bukan "database korup", karena tidak ada field database yang dibaca;
* yang dikirim pemanggil adalah pointer yang tidak bisa dipakai (0 atau UTF-16);
* `x0` (`0x45b6468e`) juga bukan pointer heap yang valid, jadi objek `this`
  sendiri sudah rusak pada jalur ini.

Kesimpulan yang bisa dipertanggungjawabkan dari bukti: `GetTexture()` dipanggil
dengan argumen nama yang tidak valid (null / encoding salah) dari jalur yang juga
membawa `this` tidak valid — konsisten dengan pemanggilan setelah database gagal
dimuat dan objeknya dibebaskan, bukan dengan bug di dalam lookup.

Perbaikan (tidak menebak offset):

* `game/Textures/GetTextureGuard.h` — `ClassifyBytes()` menolak null, string kosong,
  byte non-ASCII, dan string UTF-16 (`x2 = 0x6a006f73`) sebelum dibaca; batas copy
  128 byte; `SpanCovers()` bebas overflow untuk cek rentang.
* `game/Textures/TextureLookupGuard.cpp` — hook `_ZN22TextureDatabaseRuntime10GetTextureEPKc`
  dengan probe `/proc/self/maps` (bukan offset hardcode), menyalin nama ke buffer
  lokal supaya masa hidup string pemanggil tidak lagi berpengaruh, mencatat
  penolakan, dan mengembalikan `nullptr` alih-alih meneruskan pointer rusak.
* Dihubungkan dari `TextureVariantFallback::Initialise()` dan dilaporkan ke
  crash handler (`EngineTelemetry::GetTextureGuardInstalled`).

## 2. Root cause crash `SortEntries`

Fakta: `fault 0x3c82`, `x0` berubah-ubah, `x1 = 0`, `x2 = 0xffffffffffffffc8`
(= -56). Fault di alamat 0x3c82 berarti dereference dari nilai kecil, bukan
pointer heap: objek database atau array entry tidak valid.

Bukti pendukung dari log texture database: `player alloced=465 entries=395`
dengan `state=3`, lalu `variant 5 failed to load` **dan** `loaded` untuk database
yang sama. State yang saling bertentangan ini persis kondisi "objek ada, isi tidak":
`SortEntries` menerima database yang load-nya gagal.

Perbaikan:

* `TextureVariantDecision.h` — tabel keputusan murni: varian dianggap layak hanya
  bila `.tmb`, `.dat`, **dan** `.toc` ada dan tidak kosong; `ShouldPublishLoaded()`
  melarang database gagal dilaporkan "loaded".
* `TextureVariantFallback.cpp` — probe memakai `stat()` (ada **dan** berukuran > 0),
  mencatat ukuran tiap bagian per varian, dan mereset `numEntries` bila
  `numAlloced == 0` setelah load gagal (kondisi persis yang membuat sorter membaca
  array kosong).
* Guard sort yang sudah ada (`TextureDbGuard.h`) tetap: tolak pointer < 0x10000,
  tidak selaras, `entries > alloced`, `entries > 0x100000`, array entry kecil, dan
  entry tanpa nama.
* `TxdStore::FindTxdSlot(uint32)` sebelumnya **tidak punya `return`** (perilaku
  undefined, register kembalian berisi sampah) — sekarang mencatat log dan
  mengembalikan `-1`.

## 3. Hubungan crash dengan Custom Models

Alur yang diaudit: server → DFF → TXD → dictionary → update DB → model ID →
streaming → cleanup. Titik putusnya bukan pendaftaran model, melainkan
**kepercayaan pada isi file**: file terpotong atau bukan RenderWare tetap
didaftarkan, dictionary-nya kosong, lalu `GetTexture`/`SortEntries` menerima state
yang tidak pernah valid.

Perbaikan: `RenderWareStreamGuard.h` memvalidasi header chunk 12 byte — rentang
versi 3.x, tipe chunk (clump untuk DFF, tex dictionary untuk TXD), ukuran payload
terhadap ukuran file nyata, chunk raksasa, dan ekor data berlebih. Dipakai
`net/dl/dlmanager.cpp` sebelum registrasi. Kegagalan satu model hanya menolak model
itu (dicatat + dihitung di telemetri); **Custom Models tidak dimatikan**.

## 4. Perbaikan Texture Database

| Sebelum | Sesudah |
|---|---|
| varian dianggap ada bila `.tmb` bisa dibuka | semua bagian wajib ada dan > 0 byte |
| kegagalan load lalu "loaded" saling menimpa | state hanya naik setelah load benar-benar berhasil |
| `player` (dxt+pvr lengkap, etc tanpa `.tmb`) dilaporkan loaded | dilaporkan jujur: varian tidak lengkap, sort dilewati |
| `entries` bisa > 0 dengan `alloced = 0` | direset sebelum sorter jalan |
| forced variant (regresi lama) | `kAllowVariantSwitch = false`, `kForceVariant = false` tetap |

Tidak ada file varian yang dihapus, tidak ada ETC yang dipaksa, tidak ada
pencampuran metadata antar varian.

## 5. Perbaikan download otomatis

* `GameDataManager.verifyArchiveChecksum()` — SHA-256 arsip dibandingkan dengan
  `data_sha256` dari konfigurasi. Bila konfigurasi belum memuatnya, dicatat
  **"checksum tidak tersedia"** (bukan dianggap lolos).
* `UpdatePlan` — keputusan NOTHING / REBUILD_MANIFEST / PATCH_FILES / FULL_ARCHIVE /
  BLOCKED_NO_SPACE. Perbaikan per-berkas dipakai hanya bila ≤ 80% ukuran arsip.
* Download service: aksi `ACTION_CANCEL` (file `.part` **disimpan** supaya Range
  resume tetap bisa), baris kecepatan + sisa waktu, dan retry 5× dengan backoff
  yang sudah ada.
* `GameDataManager.startPackageDownload()` + `rewriteInstallMarker()` dipakai
  layar Pusat Data; keduanya memakai konfigurasi yang sama dengan gerbang startup.

## 6. Perbaikan manual installer

`launcher/data/ManualDataInstaller.java`:

* pemilihan arsip lewat **Storage Access Framework** (`OpenDocument`), tanpa izin
  penyimpanan;
* arsip disalin ke cache app dulu (Uri bisa berhenti terbaca saat ekstraksi panjang,
  ukuran bisa diperiksa sebelum menulis ke folder data);
* `validateArchive()` membaca central directory: arsip kosong/rusak, arsip yang
  seluruh isinya ditolak `ArchivePolicy` (zip-slip, path absolut, folder sistem);
* estimasi ruang hasil ekstrak + 5% cadangan dibanding ruang bebas;
* ekstraksi memakai `GameDataExtractor` (zip-slip canonical compare, file temp +
  rename, induk/berkas bentrok, entri 2 GB maksimum);
* persentase memakai jumlah entri nyata dari central directory, bukan timer.

Batal/ulangi: tombol batal pada unduhan, dan pemasangan ulang arsip dari picker.

## 7. Hasil validasi data

* `DataIntegrity` — SHA-256 per berkas, state `OK / MISSING / TRUNCATED / CORRUPT /
  UNREADABLE / UNVERIFIED`. `UNVERIFIED` dilaporkan apa adanya.
* `DataManifest` — manifest opsional (`data_manifest.json`) berisi versi, ukuran +
  SHA-256 arsip, dan daftar berkas; entri yang ditolak `ArchivePolicy` diabaikan.
* `DataCenterActivity` menampilkan berkas yang benar-benar diperiksa beserta status
  dan catatan "checksum belum tersedia" bila manifest belum ada.
* Bila manifest belum ada, verifikasi hanya menyatakan keberadaan berkas inti —
  dan mengatakannya.

## 8. Animasi yang diimplementasikan

`launcher/ui/MotionSpec.java` (durasi & easing konstan: 90/140/220/320/420/620 ms,
stagger 45 ms dengan cap 360 ms, `PRESS_SCALE 0.97`, `ENTRANCE_SCALE 0.94`) dan
`MotionKit` sebagai pintu tunggal: `enter`, `staggerChildren`, `crossFade`,
`pulse`, `animateProgress`, `animateCount`, `attachPressFeedback` (tidak menelan
klik/ripple), `onEnd`, `dp`, plus `reducedMotion`/`setReducedMotion`.

Dipakai di: splash (gradient + 12 mote deterministik, glow, logo spring, teks
stagger, exit scale+fade), navigasi bawah (entrance, ikon spring, label lift),
transisi halaman (cross-fade + skala kecil), dashboard, kartu settings, halaman
developer, dan layar loading. `MotionBackgroundView` berhenti saat tidak visible /
detached dan statis saat Reduce Motion aktif.

## 9. Perubahan loading screen

`game/ui/LoadingScreen.java` dibangun dari kode (tidak lagi `loadingscreen.xml`),
memakai `StartupStageModel` + polling `SAMP.nativeStartupStage()` tiap 250 ms:

* tahap nyata: data check, config, game init, RenderWare, texture database,
  textures, connect, ready (`GameStartupStage.h`, hanya maju);
* bar determinate mengikuti tahap, label berganti dengan fade;
* versi aplikasi + kredit "Pall & Fann";
* bila simbol native tidak tersedia (`UnsatisfiedLinkError`), bar disembunyikan dan
  ditampilkan "Status detail tidak tersedia di build ini" — tidak ada progress palsu.

`SAMP.showLoadingScreen()` kini benar-benar memanggil `show()`, `hide()` dipanggil di
`onStop`.

## 10. Halaman Developer Pall & Fann

`launcher/ui/DeveloperActivity.java` + `DeveloperProfile.java`: logo, judul
"HopePride Developers", kartu untuk **Pall** dan **Fann** dengan role
"Developer / App Creator", avatar inisial elegan (tanpa foto), deskripsi singkat,
versi + build (`Versi 1.7.0 (164)`), informasi proyek, copyright, dan tombol kontak
**hanya** bila `com.hopepride.samp.CONTACT_URL` benar-benar ada di manifest — bila
tidak, tampil catatan jujur bahwa tautan resmi belum tersedia. Tidak ada akun
sosial, alamat, atau identitas yang dikarang.

## 11. File yang diubah

Native (`app/src/main/cpp/samp/`): `game/Textures/{GameStartupStage.h,
GetTextureGuard.h, RenderWareStreamGuard.h, TextureLookupGuard.h,
TextureLookupGuard.cpp, TextureVariantDecision.h, TextureVariantFallback.cpp}`,
`game/{EngineTelemetry.h, EngineTelemetry.cpp, TxdStore.cpp, game.cpp}`,
`net/netgame.cpp`, `net/dl/dlmanager.cpp`, `main.cpp`.

Java (`app/src/main/java/com/sampmobile/xyvern/`): `game/{GameDataManager.java,
GameDataDownloadService.java, SAMP.java}`, `game/ui/{LoadingScreen.java,
StartupStageModel.java}`, `launcher/MainActivity.java`,
`launcher/fragments/{HomeFragment.java, SettingsFragment.java}`,
`launcher/intro/SplashActivity.java`,
`launcher/data/{ArchivePolicy, DataCenterActivity, DataIntegrity, DataManifest,
ManualDataInstaller}.java`,
`launcher/ui/{MotionSpec, MotionKit, MotionBackgroundView, DeveloperProfile,
DeveloperActivity}.java`, `launcher/update/UpdatePlan.java`.

Resource: `AndroidManifest.xml` (4 activity baru), `layout/fragment_home.xml`,
`layout/fragment_settings.xml`. Tooling: `tools/hosttest/{run.sh,
stream_guard_test.cpp}`, `tools/sourcecheck/checks.py`,
`tools/javacheck/CompileCheck.java`, `.github/workflows/hopepride-apk.yml`,
`gradle/libs.versions.toml`, `app/build.gradle.kts`.

Total: 53 file, +6.362 / −134 baris.

## 12. Hasil testing

Dijalankan dan **lulus** di CI (bukan diklaim dari lokal):

* `tools/hosttest/run.sh` — 3 binary, **0 failure**: tabel terjemahan + safe mode,
  10 assertion guard sort, 44 assertion untuk `ClassifyBytes`, `SpanCovers`,
  validasi RenderWare, keputusan varian, dan urutan tahap startup.
* `tools/sourcecheck/checks.py` — PASS. Alat ini benar-benar menangkap kesalahan
  nyata di branch ini: import basi, `R.id` tidak ada, dan arity `MotionKit.init`.
* `./gradlew testDebugUnitTest` — **119 metode `@Test` di 25 kelas, semua lulus**.
* `assembleDebug` — sukses (arm64-v8a + armeabi-v7a).

Pemetaan 27 skenario yang diminta — status **apa adanya**:

| # | Skenario | Status |
|---|---|---|
| 1 | Fresh installation | tidak diuji di perangkat |
| 2 | First launch | tidak diuji di perangkat |
| 3 | Splash screen | diimplementasi; tidak diuji di perangkat |
| 4 | Onboarding | tidak diuji di perangkat |
| 5 | Download otomatis | logika + checksum diuji unit; unduhan nyata tidak diuji |
| 6 | Instalasi manual | validasi arsip & ukuran diuji unit (10 test); SAF tidak diuji |
| 7 | Ekstraksi ZIP | diuji unit lewat `GameDataExtractor` |
| 8 | Verifikasi integritas | diuji unit (`DataIntegrityTest`, `DataManifestTest`) |
| 9 | Deteksi data hilang | diuji unit (`GameDataVerifier` dipakai Data Center) |
| 10 | Repair data | keputusan diuji unit (`UpdatePlanTest`) |
| 11 | Unduhan terputus | logika Range diuji unit; jaringan nyata tidak |
| 12 | Storage penuh | diuji unit (`BLOCKED_NO_SPACE`) |
| 13 | Arsip tidak valid | diuji unit (non-zip, zip-slip, kosong) |
| 14 | Varian database hilang | diuji host (15 assertion) |
| 15 | Texture DB load gagal | diuji host (state tidak naik ke loaded) |
| 16 | `GetTexture` argumen null | diuji host (null, kosong, UTF-16, non-ASCII) |
| 17 | `SortEntries` gagal | diuji host (10 assertion guard) |
| 18 | TXD kustom | diuji host (truncated, tipe salah, versi salah) |
| 19 | DFF kustom | diuji host (sama) |
| 20/21 | Server tanpa/dengan Custom Models | **butuh perangkat + server nyata — belum diuji** |
| 22 | Loading screen game | tidak diuji di perangkat |
| 23 | Navigasi dashboard | tidak diuji di perangkat |
| 24 | Halaman developer | logika profil diuji unit (`DeveloperProfileTest`) |
| 25 | Lifecycle background/foreground | tidak diuji di perangkat |
| 26 | Peluncuran game berulang | tidak diuji di perangkat |
| 27 | Ekspor laporan crash | kode ada; ekspor nyata tidak diuji |

## 13. Commit hash

Squash/merge ke `main`: **`223a645925082865e5c267538229a5e2a6f976ec`**
(PR #11). Pekerjaan di branch: `4f14923` (commit utama
`feat: fix HopePride data pipeline and add full motion launcher`) hingga `497bcca`.

## 14. Push status

* Branch `arena/66641683-sa-mp-hopepride-launcer` → pushed.
* PR #11 → **merged** ke `origin/main` (`223a645`).
* Build `main` (`42476428555`) → **success**.

## 15. APK path dan size

`release/HopePride-SAMP-Android.apk` — **75.774.003 byte** (≈72,3 MB),
SHA-256 `9882439ea608ab08fa7d12469875ec817588a7c1b24ca63185599c7d72b8e2b6`.
Ini **debug APK**: `app-release-key.jks` sudah ada di repo tetapi kunci itu sudah
pernah ter-commit di riwayat, jadi build release bertanda tangan tidak dibuat dan
tidak diklaim ada.

Lokasi unduh: release `apk-20261108-114157` di repositori
(`HopePride-SAMP-Android.apk`). Artifact CI `HopePride-debug` (id 13615382022) juga
tersedia sampai 2026-11-22.

## 16. Kendala yang belum terselesaikan

1. **Tidak ada perangkat/emulator di sandbox.** Semua perilaku runtime (crash fix
   pada native, animasi, SAF, lifecycle) belum diuji di perangkat. Crash 2/3 hanya
   bisa dikatakan "penyebab yang konsisten dengan bukti sudah ditangani", **bukan**
   "sudah terbukti hilang".
2. **Sandbox tidak bisa mengunduh APK.** Host `release-assets.githubusercontent.com`
   dan blob artifact diblokir (`SSL_ERROR_SYSCALL` / `EOF`), jadi file tidak ada di
   workspace; keberadaan dan ukurannya dibuktikan lewat metadata API GitHub. Jalur
   unduh untuk pengguna sudah tersedia.
3. **Perbaikan per-berkas belum bisa dieksekusi.** Sumber data saat ini satu arsip;
   `PATCH_FILES` melaporkan dengan jujur bahwa paket lengkap diperlukan, bukan
   berpura-pura menambal dua berkas.
4. **Pause/Resume unduhan** belum ada: cancel menyimpan `.part` dan resume memakai
   Range, tetapi tidak ada tombol pause yang menahan proses di tengah.
5. **`Player` varian ETC** tidak bisa diperbaiki dari sisi launcher: data pack yang
   dipakai memang tidak menyertakan `player.etc.tmb`. Launcher hanya bisa melaporkan
   dan menolak memaksa varian lain.
6. **Kredensial di riwayat git**: `app-release-key.jks`,
   `keystore_playmarket2.jks`, dan `google-services.json` pernah ter-commit. Tidak
   ditambahkan lagi, tetapi rotasi kunci/secret tetap perlu dilakukan pemilik repo.
7. **Teks UI masih hardcoded Indonesia di Java**; `strings.xml` baru 11 entri, jadi
   lokalisasi penuh belum selesai.
8. **`hope.yml`** masih pipeline rusak; hanya `hopepride-apk.yml` yang dipakai.
