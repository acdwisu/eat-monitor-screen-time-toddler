# EatMonitor Suite v3 (Zero-IP Auto-Discovery Edition)

Paket ini berisi **2 Proyek Android Studio** yang saling terhubung secara otomatis di jaringan lokal (Wi-Fi rumah atau Hotspot Tethering HP) **tanpa perlu mengetik alamat IP**:

1. **`EatMonitorChild`** (Diinstal di HP Anak):
   - Menjalankan deteksi gerakan mulut (CameraX + MediaPipe Face Landmarker) dan layar Blank Overlay.
   - Memancarkan sinyal **UDP Broadcast Beacon (Port 8889)** dan **Android NSD / mDNS (`_eatmonitor._tcp.`)** secara otomatis saat monitoring aktif.
2. **`EatMonitorRemote`** (Diinstal di HP Orang Tua):
   - Begitu aplikasi dibuka, otomatis memindai jaringan dan langsung terhubung ke HP Anak dalam < 2 detik tanpa input IP.
   - Menampilkan status real-time (`CHEWING`, `HOLDING_FOOD`, `SCREEN_LOCKED`), nilai `MAR`, dan timer `IDLE`.
   - Memiliki 3 tombol kontrol langsung:
     1. **Blank Layar Sekarang** (Layar HP anak langsung tertutup hitam, dan otomatis menyala lagi begitu anak mulai mengunyah/suapan baru).
     2. **Kunci Paksa / Force Blank** (Layar HP anak tetap hitam meskipun anak bergerak, sampai orang tua menekan tombol Unlock).
     3. **Buka Layar / Unlock** (Membuka layar hitam dan me-reset timer mengemut).

## Cara Instalasi
1. Buka folder `EatMonitorChild` di Android Studio, letakkan file `face_landmarker.task` ke `EatMonitorChild/app/src/main/assets/face_landmarker.task`, lalu Run/Install ke **HP Anak**.
2. Buka folder `EatMonitorRemote` di Android Studio (tidak butuh file model MediaPipe, sangat ringan), lalu Run/Install ke **HP Orang Tua**.
3. Pastikan kedua HP berada di Wi-Fi yang sama (atau salah satu HP menyalakan Hotspot untuk HP satunya).
