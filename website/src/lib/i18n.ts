export type Locale = "en" | "id";

export interface SignalStage {
  id: string;
  step: string;
  label: string;
  spec: string;
  detail: string;
}

export interface ScreenItem {
  id: "now-playing" | "home" | "library" | "equalizer";
  label: string;
  src: string;
  alt: string;
  width: number;
  height: number;
  title: string;
  description: string;
  specs: string[];
}

export interface FaqEntry {
  question: string;
  answer: string;
}

const en = {
  skipToContent: "Skip to main content",
  nav: {
    signalPath: "Signal path",
    features: "Features",
    privacy: "Privacy",
    download: "Download",
    faq: "FAQ",
    downloadCta: "Download APK",
    menuOpen: "Menu",
    menuClose: "Close",
    themeToLight: "Switch to light theme",
    themeToDark: "Switch to dark theme",
    languageLabel: "Switch language",
  },
  hero: {
    headline: "Your local music,\nplayed bit for bit.",
    subheadline:
      "An offline Android player for FLAC, WAV, ALAC, and MP3 libraries with a 32-bit float DSP chain, USB DAC bit-perfect output, and zero network permissions.",
    primaryCta: "Download Lumyn v1.0.0",
    secondaryCta: "View source on GitHub",
    proofLine: "Android 8.0+ · 0 network permissions · Signed APK releases",
    heroImageAlt: "Lumyn Now Playing screen playing Paradise by Coldplay with album cover colors and seekbar",
  },
  signal: {
    sectionLabel: "Honest audio routing",
    heading: "See every step between your file and your output device.",
    lead: "Most Android music apps resample tracks silently or phone home in the background. Switch modes below to inspect the real audio chain Lumyn builds inside ExoPlayer and AudioFlinger.",
    modes: {
      dsp: {
        tabLabel: "DSP 32-bit Float",
        summary: "End-to-end 32-bit PCM float chain keeping EQ, ReplayGain, and variable speed clean.",
        stages: [
          {
            id: "source",
            step: "01",
            label: "Source file",
            spec: "FLAC · WAV · ALAC · MP3 · APE · OPUS",
            detail: "Reads local tracks directly from Android MediaStore and parses VORBIS, ID3v2, and R128 ReplayGain tags even past large embedded artwork.",
          },
          {
            id: "decoder",
            step: "02",
            label: "Decoder",
            spec: "MediaCodec / NextLib FFmpeg 1.11.1",
            detail: "Uses platform hardware decoders when available and bundles NextLib FFmpeg so FLAC, ALAC, APE, WavPack, and WMA play smoothly down to Android 8.0.",
          },
          {
            id: "processor",
            step: "03",
            label: "DSP float chain",
            spec: "32-bit PCM Float · RBJ Biquad + Sonic",
            detail: "LumynAudioProcessorChain runs lock-free 10-band EQ, AutoEQ import, ReplayGain headroom, L/R balance, mono downmix, and SonicAudioProcessor speed control (0.5x to 2.0x) all in 32-bit float.",
          },
          {
            id: "output",
            step: "04",
            label: "AudioTrack output",
            spec: "PCM_FLOAT · Speed-aware crossfade",
            detail: "Hands float samples to Android AudioTrack for speaker, wired headset, Bluetooth, or USB DAC output with equal-power crossfade between tracks.",
          },
        ] as SignalStage[],
      },
      purist: {
        tabLabel: "Purist Bit-Perfect",
        summary: "Direct native sample rate and bit depth handoff for USB DACs and Android 13+ mixers.",
        stages: [
          {
            id: "source",
            step: "01",
            label: "Source file",
            spec: "Hi-Res FLAC / WAV / ALAC",
            detail: "Preserves original sample rates and bit depths without software mixing or normalization altered on disk.",
          },
          {
            id: "decoder",
            step: "02",
            label: "Decoder",
            spec: "Direct native PCM stream",
            detail: "Decodes straight to native PCM frame cadence for lossless files without intermediate float EQ stages.",
          },
          {
            id: "processor",
            step: "03",
            label: "Bypass mixer",
            spec: "Zero software DSP processing",
            detail: "Requests Android Direct PCM (API 33+) or Bit-Perfect USB routing (API 34+) and suspends playback automatically if a USB DAC is unplugged.",
          },
          {
            id: "output",
            step: "04",
            label: "USB-C DAC / Wired",
            spec: "Native hardware sample clock",
            detail: "Reports exact hardware format, sample rate, and routing status directly inside the Now Playing Audio Signal Path sheet.",
          },
        ] as SignalStage[],
      },
    },
  },
  capabilities: {
    sectionLabel: "Built for owned libraries",
    heading: "Designed for real local folders, not streaming feeds.",
    lead: "Inspect Lumyn across its four core screens while seeing the engineering modules underneath.",
    screens: [
      {
        id: "now-playing",
        label: "Now Playing",
        src: "/screenshots/now-playing.png",
        alt: "Lumyn Now Playing screen with dynamic artwork colors and symmetrical signal path controls",
        width: 720,
        height: 1507,
        title: "Adaptive Now Playing",
        description:
          "Extracts tonal Material 3 palettes directly from each album cover, shows a live Canvas wavy seekbar while playing, and keeps quick access to lyrics, 10-band EQ, sleep timer, playback speed, queue, and signal diagnostics.",
        specs: ["Dynamic artwork seed extraction", "0.5x to 2.0x speed control", "Audio Signal Path sheet"],
      },
      {
        id: "home",
        label: "Home",
        src: "/screenshots/home.png",
        alt: "Lumyn Home screen showing song stats, smart playlists, album spotlight, and mini player",
        width: 720,
        height: 1507,
        title: "Library Overview & Smart Lists",
        description:
          "Launches straight into your scanned local collection with quick shuffle, local Listening Insights stored exclusively in Room on your device, Album Spotlight, and automatic Smart Playlists.",
        specs: ["Room local play history", "Forgotten Gems & Hi-Res picks", "Floating persistent mini-player"],
      },
      {
        id: "library",
        label: "Library",
        src: "/screenshots/library.png",
        alt: "Lumyn Library Artists tab with expressive Material 3 shape frames",
        width: 720,
        height: 1507,
        title: "Folders, Tags & Virtual CUE",
        description:
          "Browse by songs, albums, artists, folders, or playlists. Edit FLAC, MP3, and MP4/M4A metadata tags and album art directly inside the files with Android 11+ MediaStore write consent, or split single-file .cue rips virtually.",
        specs: ["Pure-Kotlin FLAC / ID3v2 / MP4 writer", "M3U8 import & export", "Folder whitelist / blacklist"],
      },
      {
        id: "equalizer",
        label: "Equalizer & DSP",
        src: "/screenshots/equalizer.png",
        alt: "Lumyn Equalizer and DSP screen displaying 10-band sliders and AutoEQ import",
        width: 720,
        height: 1507,
        title: "10-Band EQ, AutoEQ & Mix Controls",
        description:
          "Fine-tune playback with lock-free RBJ biquad filters, built-in headphone/speaker presets, parametric AutoEQ .txt profile imports, ReplayGain Track/Album preamp, L/R channel balance, mono downmix, and crossfade.",
        specs: ["32-bit float headroom", "AutoEQ .txt parser", "Zero-drop live updates"],
      },
    ] as ScreenItem[],
    pillarsHeading: "Also built in",
    pillars: [
      {
        title: "In-File Tag & Cover Editor",
        detail: "Streaming Kotlin writers modify FLAC Vorbis comments, ID3v2 frames, and MP4 ilst atoms safely with chunk offset shifting.",
      },
      {
        title: "Android Auto & Home Widget",
        detail: "Includes MediaLibraryService browsing for cars plus a Jetpack Glance widget synced to active playback state.",
      },
      {
        title: "Material 3 Expressive & Adaptive UI",
        detail: "Built with Jetpack Compose Material 3 1.5.0 supporting responsive panes for phones, foldables, and tablets.",
      },
    ],
  },
  tagline: {
    lines: ["Music you already own", "deserves a player that never phones home."],
  },
  privacy: {
    sectionLabel: "Privacy by architecture",
    heading: "Zero network permissions in debug and release builds.",
    body: "Lumyn declares neither INTERNET nor ACCESS_NETWORK_STATE in AndroidManifest.xml. There are no analytics SDKs, no crash reporters, no account prompts, and no telemetry endpoints anywhere in the codebase.",
    verifyLabel: "Verify any release APK yourself with Android Build Tools:",
    verifyCommand: "aapt2 dump permissions Lumyn-v1.0.0-arm64-v8a.apk",
    copyCmd: "Copy command",
    copiedCmd: "Command copied",
    policyLink: "Read full privacy notes",
    checklistTitle: "Removed from merged AndroidManifest.xml",
    checklist: [
      "android.permission.INTERNET (never declared)",
      "android.permission.ACCESS_NETWORK_STATE (explicitly removed)",
      "Verified on every CI build before publishing APKs",
    ],
  },
  download: {
    sectionLabel: "Signed GitHub releases",
    heading: "Download Lumyn for Android.",
    lead: "Every APK is signed with the Dawn Studio release key, scanned by aapt2 to confirm zero network permissions, and paired with SHA-256 checksums.",
    checkLatest: "Check latest release",
    checkingLatest: "Checking GitHub...",
    sourceLive: "Synced with GitHub Releases",
    sourceFallback: "Showing bundled v1.0.0 release record",
    retryHint: "Could not reach GitHub API right now. Cached v1.0.0 release assets remain ready below.",
    archLabel: "Choose APK build",
    recommendedBadge: "Recommended",
    downloadApk: "Download APK",
    viewAllReleases: "View release notes on GitHub",
    fileNameLabel: "Filename",
    sizeLabel: "Size",
    versionLabel: "Version",
    shaLabel: "SHA-256 checksum",
    copySha: "Copy SHA-256",
    copiedSha: "SHA-256 copied",
    loadingState: "Loading release metadata from Dawnistrator/Lumyn...",
    emptyState: "No matching APK assets were returned for this release yet.",
  },
  faq: {
    sectionLabel: "Questions & answers",
    heading: "Technical details before you install.",
    items: [
      {
        question: "Which APK file should I download for my phone?",
        answer:
          "Download arm64-v8a for almost every Android phone released in the last eight years (smallest download at 17.13 MB). Choose universal if you are unsure of your CPU architecture, armeabi-v7a for older 32-bit ARM devices, or x86_64 / x86 for emulators and ChromeOS.",
      },
      {
        question: "Does Lumyn really use zero internet permissions?",
        answer:
          "Yes. Both debug and release manifests omit INTERNET and explicitly strip ACCESS_NETWORK_STATE via manifest merger rules. You can verify the compiled APK locally with aapt2 dump permissions before installing.",
      },
      {
        question: "Which audio formats work on older Android versions?",
        answer:
          "Alongside Android platform codecs, Lumyn bundles NextLib FFmpeg 1.11.1-0.19.0 so lossless FLAC, ALAC, APE, WavPack, WMA, AAC, and MP3 files decode cleanly all the way down to Android 8.0 Oreo (API 26).",
      },
      {
        question: "What is the difference between DSP 32-bit Float and Purist Bit-Perfect mode?",
        answer:
          "DSP 32-bit Float processes audio inside a custom Media3 AudioProcessorChain in floating-point headroom so 10-band EQ, AutoEQ, ReplayGain, L/R balance, mono, playback speed, and crossfade work together cleanly. Purist Bit-Perfect bypasses software EQ and requests Direct PCM (Android 13+) or Bit-Perfect USB DAC output (Android 14+).",
      },
      {
        question: "Does Playback Speed work together with the 32-bit Float Equalizer?",
        answer:
          "Yes. SonicAudioProcessor runs in 32-bit PCM float right after LumynDspAudioProcessor inside a unified AudioProcessorChain, allowing 0.5x to 2.0x speed adjustment while keeping EQ active and AudioTrack output in PCM_FLOAT.",
      },
      {
        question: "How does in-file tag and album art editing work on modern Android?",
        answer:
          "On Android 11+, Lumyn requests system MediaStore.createWriteRequest permission per modified file and writes FLAC Vorbis comments/pictures, MP3 ID3v2 frames/APIC, or MP4 ilst atoms directly to disk without altering audio frames.",
      },
      {
        question: "Is Lumyn free to inspect and build from source?",
        answer:
          "Yes. Full Kotlin source code across all 12 Gradle modules is available at Dawnistrator/Lumyn on GitHub, alongside the Gradle build instructions and CI workflow.",
      },
    ] as FaqEntry[],
  },
  footer: {
    ctaHeading: "Ready to keep your music collection offline?",
    ctaButton: "Download Lumyn v1.0.0",
    note: "Built in Kotlin with Jetpack Compose Material 3 Expressive and Jetpack Media3.",
    links: {
      github: "GitHub Repository",
      releases: "Releases",
      privacy: "Privacy Policy",
      issues: "Report an Issue",
    },
  },
  privacyPage: {
    backHome: "Back to Lumyn home",
    title: "Privacy Policy",
    subtitle: "Offline by architecture, verified on every compiled APK.",
    updated: "Effective with release v1.0.0",
    sections: [
      {
        heading: "1. Zero Network Access",
        body: "Lumyn does not declare the android.permission.INTERNET permission and explicitly removes android.permission.ACCESS_NETWORK_STATE in AndroidManifest.xml. The Android application cannot open sockets, transmit analytics, load remote album artwork, or contact external servers.",
      },
      {
        heading: "2. Local Data Storage Only",
        body: "Track metadata, playlists, play counts, queue state, ReplayGain measurements, and equalizer presets remain exclusively inside private app storage (Room database and Preferences DataStore) on your device. Removing the app clears this database unless Android Auto Backup is enabled by your system settings.",
      },
      {
        heading: "3. Media & Device Permissions",
        body: "Lumyn requests only permissions required for local audio playback: READ_MEDIA_AUDIO (or READ_EXTERNAL_STORAGE on Android 12 and lower) to index music files, FOREGROUND_SERVICE_MEDIA_PLAYBACK and POST_NOTIFICATIONS for background playback controls, WAKE_LOCK to keep audio uninterrupted while the screen is off, and optional BLUETOOTH_CONNECT to display active Bluetooth device names in the Audio Signal Path sheet. Writing tags on Android 11+ uses explicit per-file MediaStore.createWriteRequest system prompts.",
      },
      {
        heading: "4. Independent Verification",
        body: "You can inspect every published APK binary directly with Android Build Tools (`aapt2 dump permissions`) or review the full source code on GitHub before installing.",
      },
    ],
  },
  notFound: {
    code: "404",
    title: "Page not found",
    description: "This route does not exist on the Lumyn site. Use the link below to return to the player overview and APK downloads.",
    backHome: "Return to home",
  },
};

export type Copy = typeof en;

const id: Copy = {
  skipToContent: "Langsung ke konten utama",
  nav: {
    signalPath: "Jalur sinyal",
    features: "Fitur",
    privacy: "Privasi",
    download: "Unduh",
    faq: "FAQ",
    downloadCta: "Unduh APK",
    menuOpen: "Menu",
    menuClose: "Tutup",
    themeToLight: "Ganti ke tema terang",
    themeToDark: "Ganti ke tema gelap",
    languageLabel: "Ganti bahasa",
  },
  hero: {
    headline: "Musik lokal Anda,\ndiputar apa adanya.",
    subheadline:
      "Pemutar musik Android offline untuk koleksi FLAC, WAV, ALAC, dan MP3 dengan mesin DSP 32-bit float, output USB DAC bit-perfect, serta nol izin internet.",
    primaryCta: "Unduh Lumyn v1.0.0",
    secondaryCta: "Lihat kode di GitHub",
    proofLine: "Android 8.0+ · 0 izin jaringan · Rilis APK bertanda tangan resmi",
    heroImageAlt: "Tampilan layar Now Playing Lumyn memutar lagu Paradise dari Coldplay dengan warna dinamis sampul album",
  },
  signal: {
    sectionLabel: "Transparansi jalur audio",
    heading: "Lihat setiap tahap antara file lokal dan perangkat output Anda.",
    lead: "Banyak pemutar audio melakukan resampling diam-diam atau mengirim data di latar belakang. Pindah mode di bawah untuk melihat jalur audio nyata yang dijalankan Lumyn di dalam ExoPlayer dan AudioFlinger.",
    modes: {
      dsp: {
        tabLabel: "DSP 32-bit Float",
        summary: "Pipeline 32-bit PCM float dari awal hingga AudioTrack agar EQ, ReplayGain, dan speed tetap presisi.",
        stages: [
          {
            id: "source",
            step: "01",
            label: "File sumber",
            spec: "FLAC · WAV · ALAC · MP3 · APE · OPUS",
            detail: "Membaca lagu lokal langsung dari Android MediaStore dan mengurai tag VORBIS, ID3v2, dan R128 ReplayGain meski di balik gambar sampul berukuran besar.",
          },
          {
            id: "decoder",
            step: "02",
            label: "Decoder",
            spec: "MediaCodec / NextLib FFmpeg 1.11.1",
            detail: "Memakai decoder perangkat keras saat tersedia serta NextLib FFmpeg bawaan agar FLAC, ALAC, APE, WavPack, dan WMA diputar mulus hingga Android 8.0.",
          },
          {
            id: "processor",
            step: "03",
            label: "Rantai DSP float",
            spec: "32-bit PCM Float · RBJ Biquad + Sonic",
            detail: "LumynAudioProcessorChain menjalankan equalizer 10-band, impor AutoEQ, headroom ReplayGain, keseimbangan L/R, downmix mono, dan kecepatan 0.5x hingga 2.0x dalam 32-bit float.",
          },
          {
            id: "output",
            step: "04",
            label: "Output AudioTrack",
            spec: "PCM_FLOAT · Crossfade adaptif",
            detail: "Mengirim sampel float langsung ke AudioTrack untuk speaker, earphone kabel, Bluetooth, atau USB DAC dengan crossfade equal-power yang menyesuaikan kecepatan putar.",
          },
        ] as SignalStage[],
      },
      purist: {
        tabLabel: "Purist Bit-Perfect",
        summary: "Penyerahan sample rate dan bit depth asli langsung ke USB DAC atau mixer Android 13+.",
        stages: [
          {
            id: "source",
            step: "01",
            label: "File sumber",
            spec: "Hi-Res FLAC / WAV / ALAC",
            detail: "Mempertahankan sample rate serta bit depth asli file tanpa pemrosesan gain atau resampling perangkat lunak.",
          },
          {
            id: "decoder",
            step: "02",
            label: "Decoder",
            spec: "Aliran PCM asli",
            detail: "Mendekode langsung ke aliran PCM asli tanpa melewati tahap filter float DSP.",
          },
          {
            id: "processor",
            step: "03",
            label: "Lewati mixer",
            spec: "Nol pemrosesan DSP software",
            detail: "Meminta rute Direct PCM (Android 13+) atau Bit-Perfect USB (Android 14+) dan menjeda pemutaran otomatis jika USB DAC dicabut.",
          },
          {
            id: "output",
            step: "04",
            label: "USB-C DAC / Kabel",
            spec: "Clock perangkat keras asli",
            detail: "Menampilkan format, sample rate, dan status perangkat output secara transparan di sheet Audio Signal Path.",
          },
        ] as SignalStage[],
      },
    },
  },
  capabilities: {
    sectionLabel: "Dirancang untuk koleksi pribadi",
    heading: "Fokus pada folder musik lokal, bukan algoritma streaming.",
    lead: "Jelajahi empat layar utama Lumyn beserta kemampuan teknis di baliknya.",
    screens: [
      {
        id: "now-playing",
        label: "Now Playing",
        src: "/screenshots/now-playing.png",
        alt: "Layar Now Playing Lumyn dengan warna adaptif sampul album dan kontrol sejajar",
        width: 720,
        height: 1507,
        title: "Now Playing Adaptif",
        description:
          "Menghasilkan palet warna Material 3 dari sampul album yang sedang diputar, seekbar gelombang Canvas saat audio berjalan, serta akses cepat ke lirik, EQ 10-band, timer tidur, kecepatan putar, antrean, dan diagnostik sinyal.",
        specs: ["Ekstraksi warna sampul album", "Kecepatan putar 0.5x hingga 2.0x", "Sheet Audio Signal Path"],
      },
      {
        id: "home",
        label: "Home",
        src: "/screenshots/home.png",
        alt: "Layar Home Lumyn menampilkan ringkasan lagu, smart playlist, spotlight album, dan mini player",
        width: 720,
        height: 1507,
        title: "Ringkasan Koleksi & Smart Playlist",
        description:
          "Membuka langsung daftar lagu lokal yang sudah dipindai lengkap dengan tombol acak cepat, Listening Insights yang tersimpan hanya di Room perangkat Anda, Album Spotlight, dan Smart Playlist otomatis.",
        specs: ["Riwayat putar Room lokal", "Koleksi Hi-Res & Forgotten Gems", "Mini-player melayang persisten"],
      },
      {
        id: "library",
        label: "Library",
        src: "/screenshots/library.png",
        alt: "Layar Library tab Artists di Lumyn dengan bingkai avatar Material 3",
        width: 720,
        height: 1507,
        title: "Folder, Editor Tag & Virtual CUE",
        description:
          "Telusuri lagu, album, artis, folder, atau playlist. Ubah metadata tag serta sampul album langsung di file FLAC, MP3, dan MP4/M4A memakai izin tulis Android 11+ MediaStore, atau pecah file rip .cue secara virtual.",
        specs: ["Penulis tag FLAC / ID3v2 / MP4", "Impor & ekspor M3U8", "Filter folder whitelist / blacklist"],
      },
      {
        id: "equalizer",
        label: "Equalizer & DSP",
        src: "/screenshots/equalizer.png",
        alt: "Layar Equalizer dan DSP Lumyn menampilkan slider 10-band dan impor AutoEQ",
        width: 720,
        height: 1507,
        title: "Equalizer 10-Band, AutoEQ & Mix",
        description:
          "Atur suara secara langsung memakai filter RBJ biquad tanpa putus suara, preset bawaan, impor file parametrik AutoEQ .txt, preamp ReplayGain Track/Album, keseimbangan saluran L/R, audio mono, dan crossfade.",
        specs: ["Headroom 32-bit float", "Parser profil AutoEQ .txt", "Pembaruan DSP real-time"],
      },
    ] as ScreenItem[],
    pillarsHeading: "Fitur pelengkap sistem",
    pillars: [
      {
        title: "Editor Tag & Sampul di Dalam File",
        detail: "Ditulis langsung dalam Kotlin untuk komentar Vorbis FLAC, frame ID3v2 MP3, dan atom ilst MP4 dengan pergeseran offset stco/co64.",
      },
      {
        title: "Android Auto & Widget Layar Utama",
        detail: "Mendukung browsing MediaLibraryService di Android Auto serta widget Jetpack Glance yang tersinkronisasi dengan pemutaran aktif.",
      },
      {
        title: "Material 3 Expressive & Layar Adaptif",
        detail: "Dibangun dengan Jetpack Compose Material 3 1.5.0 dan tata letak responsif untuk ponsel, layar lipat, maupun tablet.",
      },
    ],
  },
  tagline: {
    lines: ["Musik yang sudah Anda miliki", "layak diputar tanpa aplikasi yang menghubungi server luar."],
  },
  privacy: {
    sectionLabel: "Privasi lewat arsitektur",
    heading: "Nol izin jaringan pada build debug maupun release.",
    body: "Lumyn tidak mendeklarasikan izin INTERNET dan menghapus ACCESS_NETWORK_STATE secara eksplisit di AndroidManifest.xml. Tidak ada SDK analitik, pelapor crash, akun login, atau koneksi keluar di seluruh kode aplikasi.",
    verifyLabel: "Verifikasi APK rilis mana pun memakai Android Build Tools:",
    verifyCommand: "aapt2 dump permissions Lumyn-v1.0.0-arm64-v8a.apk",
    copyCmd: "Salin perintah",
    copiedCmd: "Perintah disalin",
    policyLink: "Baca catatan privasi lengkap",
    checklistTitle: "Dihapus dari AndroidManifest.xml gabungan",
    checklist: [
      "android.permission.INTERNET (tidak pernah dideklarasikan)",
      "android.permission.ACCESS_NETWORK_STATE (dihapus eksplisit)",
      "Diperiksa otomatis pada setiap rilis APK di GitHub Actions",
    ],
  },
  download: {
    sectionLabel: "Rilis GitHub bertanda tangan",
    heading: "Unduh Lumyn untuk Android.",
    lead: "Setiap APK ditandatangani dengan kunci rilis Dawn Studio, diperiksa menggunakan aapt2 untuk memastikan nol izin internet, dan disertai checksum SHA-256.",
    checkLatest: "Periksa rilis terbaru",
    checkingLatest: "Memeriksa GitHub...",
    sourceLive: "Tersinkronisasi dengan GitHub Releases",
    sourceFallback: "Menampilkan arsip data rilis v1.0.0",
    retryHint: "API GitHub belum bisa dijangkau saat ini. Asset rilis v1.0.0 tetap siap diunduh di bawah.",
    archLabel: "Pilih arsitektur APK",
    recommendedBadge: "Disarankan",
    downloadApk: "Unduh APK",
    viewAllReleases: "Lihat catatan rilis di GitHub",
    fileNameLabel: "Nama file",
    sizeLabel: "Ukuran",
    versionLabel: "Versi",
    shaLabel: "Checksum SHA-256",
    copySha: "Salin SHA-256",
    copiedSha: "SHA-256 disalin",
    loadingState: "Memuat metadata rilis terbaru dari Dawnistrator/Lumyn...",
    emptyState: "Belum ada asset APK yang tersedia untuk rilis ini.",
  },
  faq: {
    sectionLabel: "Tanya & jawab",
    heading: "Detail teknis sebelum Anda memasang.",
    items: [
      {
        question: "File APK mana yang sebaiknya saya unduh untuk ponsel saya?",
        answer:
          "Pilih arm64-v8a untuk hampir semua ponsel Android modern keluaran delapan tahun terakhir (ukuran unduhan paling ringkas, 17.13 MB). Pilih universal jika ragu soal arsitektur CPU, armeabi-v7a untuk perangkat ARM 32-bit lama, atau x86_64 / x86 untuk emulator dan ChromeOS.",
      },
      {
        question: "Apakah Lumyn benar-benar tidak memiliki izin akses internet?",
        answer:
          "Ya. Baik manifest debug maupun release tidak meminta izin INTERNET dan secara eksplisit menghapus ACCESS_NETWORK_STATE lewat aturan manifest merger. Anda dapat membuktikannya langsung pada file APK memakai perintah aapt2 dump permissions.",
      },
      {
        question: "Format audio apa saja yang didukung pada Android versi lama?",
        answer:
          "Selain codec bawaan Android, Lumyn menyertakan NextLib FFmpeg 1.11.1-0.19.0 sehingga format lossless FLAC, ALAC, APE, WavPack, WMA, AAC, hingga MP3 dapat diputar mulus sejak Android 8.0 Oreo (API 26).",
      },
      {
        question: "Apa perbedaan mode DSP 32-bit Float dan Purist Bit-Perfect?",
        answer:
          "Mode DSP 32-bit Float memproses suara di dalam AudioProcessorChain 32-bit float agar Equalizer 10-band, AutoEQ, ReplayGain, L/R balance, mono, playback speed, dan crossfade berjalan bersamaan. Mode Purist Bit-Perfect melewati EQ software untuk meminta rute Direct PCM (Android 13+) atau Bit-Perfect USB DAC (Android 14+).",
      },
      {
        question: "Apakah Playback Speed tetap berfungsi saat Equalizer 32-bit Float aktif?",
        answer:
          "Ya. SonicAudioProcessor berjalan langsung dalam mode 32-bit PCM float setelah LumynDspAudioProcessor di dalam AudioProcessorChain yang sama, sehingga pengaturan kecepatan 0.5x sampai 2.0x bekerja mulus tanpa menurunkan output AudioTrack PCM_FLOAT.",
      },
      {
        question: "Bagaimana cara kerja editor tag dan sampul album pada Android 11+?",
        answer:
          "Pada Android 11 ke atas, Lumyn meminta izin tulis sistem MediaStore.createWriteRequest untuk file terkait lalu memperbarui komentar Vorbis FLAC, frame ID3v2 MP3, atau atom ilst MP4 langsung di penyimpanan lokal tanpa mengubah frame audio.",
      },
      {
        question: "Apakah Lumyn gratis dan terbuka kode sumbernya?",
        answer:
          "Ya. Seluruh kode Kotlin di 12 modul Gradle tersedia di repositori GitHub Dawnistrator/Lumyn lengkap dengan skrip build dan workflow rilis otomatis.",
      },
    ] as FaqEntry[],
  },
  footer: {
    ctaHeading: "Siap menjaga koleksi musik Anda tetap offline?",
    ctaButton: "Unduh Lumyn v1.0.0",
    note: "Dibangun dengan Kotlin, Jetpack Compose Material 3 Expressive, dan Jetpack Media3.",
    links: {
      github: "Repositori GitHub",
      releases: "Rilis APK",
      privacy: "Kebijakan Privasi",
      issues: "Laporkan Masalah",
    },
  },
  privacyPage: {
    backHome: "Kembali ke beranda Lumyn",
    title: "Kebijakan Privasi",
    subtitle: "Offline sejak arsitektur dasar, dapat diverifikasi pada setiap file APK.",
    updated: "Berlaku mulai rilis v1.0.0",
    sections: [
      {
        heading: "1. Nol Akses Jaringan",
        body: "Lumyn tidak mendeklarasikan izin android.permission.INTERNET dan secara eksplisit menghapus android.permission.ACCESS_NETWORK_STATE di AndroidManifest.xml. Aplikasi Android ini tidak dapat membuka koneksi jaringan, mengirim analitik, mengunduh sampul otomatis, maupun menghubungi server mana pun.",
      },
      {
        heading: "2. Penyimpanan Lokal di Perangkat",
        body: "Metadata lagu, playlist, riwayat putar, status antrean, nilai ReplayGain, dan pengaturan equalizer disimpan seluruhnya di penyimpanan privat aplikasi (database Room dan Preferences DataStore) pada perangkat Anda sendiri.",
      },
      {
        heading: "3. Izin Media & Perangkat",
        body: "Lumyn hanya meminta izin untuk memutar file lokal: READ_MEDIA_AUDIO (atau READ_EXTERNAL_STORAGE pada Android 12 ke bawah) untuk memindai musik, FOREGROUND_SERVICE_MEDIA_PLAYBACK serta POST_NOTIFICATIONS untuk kontrol pemutaran latar belakang, WAKE_LOCK agar pemutaran tidak terputus saat layar mati, dan BLUETOOTH_CONNECT opsional untuk menampilkan nama perangkat Bluetooth pada sheet Audio Signal Path.",
      },
      {
        heading: "4. Verifikasi Independen",
        body: "Anda dapat memeriksa izin setiap APK rilis dengan Android Build Tools (`aapt2 dump permissions`) atau meninjau seluruh kode sumber secara terbuka di GitHub sebelum memasangnya.",
      },
    ],
  },
  notFound: {
    code: "404",
    title: "Halaman tidak ditemukan",
    description: "Rute yang Anda buka tidak tersedia di situs Lumyn. Gunakan tombol di bawah untuk kembali ke halaman utama dan unduhan APK.",
    backHome: "Kembali ke beranda",
  },
};

export const dictionaries: Record<Locale, Copy> = { en, id };
