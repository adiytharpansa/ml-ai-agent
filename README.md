# Uji Jalur ML — Kerangka Fase 0

Kerangka aplikasi Android untuk proyek **AI memainkan Mobile Legends sendiri (rancangan sendiri)**,
jalur HP saja. Ini adalah **Fase 0** dari dokumen rancangan
(`~/workspace/your_files/rancangan-ai-mobile-legends/RANCANGAN-AI-MOBILE-LEGENDS.md`):
membuktikan jalur di HP pengguna (Samsung A15) — rekam layar, sentuh, Shizuku —
**sebelum** otak permainan apa pun ditulis.

Satu tombol, **Uji Mandiri Fase 0**, menjalankan percobaan-percobaan Fase 0 secara
berurutan dan menulis vonisnya sendiri (LULUS / GAGAL / BELUM + angka buktinya)
di panel Diagnostik dan ke berkas `diagnostics.txt`.
Yang belum benar-benar diukur selalu dilaporkan **BELUM** — tidak pernah dihias.

- Paket: `id.rancangan.mlai` · Label: **Uji Jalur ML**
- minSdk 29 · target/compileSdk 34 · Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.13 (wrapper)
- Satu modul `:app`, tampilan Views biasa (tanpa Compose) agar ringan.

## Peta modul (vs dokumen rancangan)

| Bagian rancangan | Di proyek ini |
|---|---|
| Modul 1 — Mata | `capture/CaptureService.kt` (MediaProjection + ImageReader, lebar maks 960), `capture/CaptureStats.kt` (akumulator statistik + keadaan bersama), `vision/FrameLuminance.kt` (matematika luminans untuk deteksi frame hitam) |
| Modul 3 — Tangan | `touch/TouchBackend.kt` (antarmuka perkakas), `touch/AccessibilityTouchBackend.kt` (jalur cadangan), `touch/ShizukuTouchBackend.kt` + `touch/ShizukuState.kt` (jalur utama — injeksi InputManager sungguhan), `touch/InputManagerLink.kt` (jembatan reflektif ke IInputManager lewat ShizukuBinderWrapper), `touch/PointerTracker.kt` (pembukuan penunjuk ganda murni, teruji unit), `accessibility/MlAccessibilityService.kt`, `touch/ScriptedTouchTest.kt` (naskah uji tetap), `touch/GesturePlan.kt` (aturan waktu segmen 200–500 mdtk) |
| Modul 7 — Catatan & Uji | `diag/SelfTest.kt` (Uji Mandiri), `diag/EvidenceFiles.kt` (penulis bukti), `diag/DeviceHealth.kt` (suhu/baterai BatteryManager), `canvas/` (Kanvas Uji: target sentuh milik sendiri) |
| Modul 8 — Pengawas | Sebagian kecil: tombol Berhenti di panel + aksi Berhenti di notifikasi layanan tangkap. Penjaga mode/suhu penuh datang di fase berikutnya. |
| Modul 2, 4, 5, 6 | **Belum ada** — memang bukan Fase 0. |

## Berkas bukti — selalu dua salinan

`diagnostics.txt` dan `sample_frame.png` ditulis ke **dua** tempat dari satu fungsi
(`diag/EvidenceFiles.kt`, dipakai juga oleh CaptureService):

1. `filesDir` internal aplikasi, dan
2. direktori berkas eksternal khusus aplikasi
   (`Android/data/id.rancangan.mlai/files/`) — salinan ini yang bisa ditarik dengan
   `adb pull` tanpa root saat pengujian langsung lewat ADB.

## Status jujur per potong

| Potong | Status |
|---|---|
| Panel status (izin rekam, aksesibilitas, Shizuku, statistik tangkap) | Ditulis lengkap; **belum terverifikasi build** (lihat bawah) |
| CaptureService (fps, rasio frame hitam, PNG contoh) | Ditulis lengkap; belum terverifikasi build/perangkat |
| Layanan Aksesibilitas + backend sentuh (ketuk/geser; joystick = rantai segmen `willContinue`) | Ditulis lengkap; batasnya didokumentasikan di kode: gerakan terencana di muka, skill **tidak** benar-benar bersamaan dengan joystick di jalur ini |
| Kanvas Uji (joystick + 3 skill, log MotionEvent per penunjuk) | Ditulis lengkap |
| Uji Mandiri Fase 0 (6 vonis: aksesibilitas, rekam, sentuh→kanvas, Shizuku, simultan, suhu/baterai) | Ditulis lengkap; vonis "simultan joystick+skill" kini **dijalankan sungguhan** bila Shizuku siap: joystick ditahan di Kanvas Uji, skill diketuk penunjuk kedua, dan LULUS hanya dari bukti log kanvas (`maxPointerCount ≥ 2`). Bila Shizuku belum siap, vonis tetap **BELUM** dengan alasannya |
| Backend sentuh Shizuku (injeksi InputManager, penunjuk ganda mengalir) | **Diimplementasikan.** Lewat ShizukuBinderWrapper → `IInputManager.injectInputEvent` (refleksi, tanpa stub API tersembunyi), mode ASYNC. Penunjuk 0 = joystick (ditahan, MOVE mengalir, gerakan digabung *latest-wins*), penunjuk 1 = skill/ketuk (POINTER_DOWN/UP di tengah tahanan). Semua injeksi di satu HandlerThread khusus; setiap metode mengembalikan hasil boolean injeksi yang sebenarnya, dan kegagalan di tengah aliran memicu CANCEL pemulihan + `lastError` tercatat. **Belum terverifikasi build/perangkat** — pembuktiannya menunggu CI pertama dan percobaan Fase 0 di A15. |
| Tes unit (luminans, akumulator statistik, aturan segmen gestur, pembukuan penunjuk) | 4 kelas JUnit, logika murni |

## Cara membangun

Lingkungan kerja Muse **tidak bisa menjalankan Gradle secara lokal** (daemon Gradle
memakai TCP mentah yang diblokir di sini), jadi verifikasi resmi adalah **CI GitHub Actions**
(`.github/workflows/ci.yml`): JDK 17 → `:app:testDebugUnitTest` → `:app:assembleDebug` →
artefak APK debug diunggah. Kerangka ini ditulis dengan hati-hati mengikuti pola proyek
Android standar, tetapi **belum dinyatakan lolos build** sampai CI pertama hijau —
langkah berikutnya adalah membuat repo GitHub, mendorong proyek ini, dan membaca vonis CI.
