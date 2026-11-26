# Lumyn Web Design System

## Direction
- **Product:** Offline Hi-Res Android music player for local FLAC, WAV, ALAC, and MP3 libraries with a 32-bit float DSP chain, bit-perfect USB DAC output, and zero network permissions.
- **Character:** Calm, exact, tactile (audio studio instrumentation rather than generic SaaS).
- **World:** Dark obsidian slate tinted toward deep studio teal (`#0B1417`) anchored by Lumyn's native seed color (`#0E6178`), paired with an honest daytime light slate mode (`#F5FAFC`).
- **Moments:**
  1. Interactive Audio Signal Path switcher (`DSP 32-bit Float` vs `Purist Bit-Perfect`) detailing each real stage from local file to DAC.
  2. Live APK selector (`arm64-v8a`, `universal`, `armeabi-v7a`, `x86_64`, `x86`) with real GitHub Release asset sizes, SHA-256 copy, and `aapt2` zero-permission proof.
- **Signature:** Five curved audio-level bars from `ic_launcher_foreground.xml` used as the brand mark, favicon, and active pipeline indicator.
- **Dials:** `ENERGY 2 (Balanced)` / `RHYTHM 3 (Varied)` / `MOTION 2 (Purposeful transitions & word-by-word tagline reveal)`

## Color Tokens (WCAG AA Pre-Verified)

### Dark Theme (`[data-theme="dark"]`, Default)
| Token | Hex / Value | Usage |
| :--- | :--- | :--- |
| `--bg` | `#0B1417` | Page background (`lumyn_window_background` night) |
| `--surface` | `#121E22` | Cards, navigation sheet, code panels |
| `--surface-raised` | `#1A2A30` | Active tabs, hover surfaces, raised controls |
| `--text-primary` | `#F5FAFC` | Primary headlines and body (17.72:1 on `--bg`) |
| `--text-secondary` | `#B3C7CE` | Supporting prose (10.63:1 on `--bg`) |
| `--text-muted` | `#93ABB4` | Captions and metadata (7.74:1 on `--bg`) |
| `--accent` | `#67C6DF` | Selected state highlights and links on dark surfaces (9.51:1 on `--bg`) |
| `--accent-fill` | `#0E6178` | Brand seed solid fill (`LumynBrandSeedArgb`) |
| `--on-accent` | `#F5FAFC` | Text over `#0E6178` fill (6.65:1) |
| `--ui-border` | `#6B8892` | Interactive control borders (4.51:1 on `--surface`) |
| `--hairline` | `rgba(245, 250, 252, 0.12)` | Subtle full-perimeter card dividers |
| `--focus-ring` | `#67C6DF` | Keyboard focus ring |

### Light Theme (`[data-theme="light"]`)
| Token | Hex / Value | Usage |
| :--- | :--- | :--- |
| `--bg` | `#F5FAFC` | Page background (`lumyn_window_background` day) |
| `--surface` | `#FFFFFF` | Cards and panels |
| `--surface-raised` | `#E7F0F3` | Raised rows, active tabs, hover states |
| `--text-primary` | `#0B171B` | Primary headlines and body (17.32:1 on `--bg`) |
| `--text-secondary` | `#354A51` | Supporting prose (8.87:1 on `--bg`) |
| `--text-muted` | `#496068` | Captions and metadata (6.32:1 on `--bg`) |
| `--accent` | `#0B566B` | Accessible dark teal links/accents on light surfaces (7.80:1 on `--bg`) |
| `--accent-fill` | `#0E6178` | Primary brand fill |
| `--on-accent` | `#F5FAFC` | Text over `#0E6178` fill (6.65:1) |
| `--ui-border` | `#6F868E` | Interactive control borders (3.83:1 on `--surface`) |
| `--hairline` | `rgba(11, 23, 27, 0.12)` | Full-perimeter card borders |
| `--focus-ring` | `#0B566B` | Keyboard focus ring |

## Typography
- **Primary (`--font-sans`):** `Geist` (clean geometric readability in English and Indonesian; chosen over Android-bundled monospace body to ensure long-form technical legibility).
- **Monospace (`--font-mono`):** `Geist Mono` with `tabular-nums`, used exclusively for sample rates, bit depths, APK byte sizes, `SHA-256` digests, and CLI verification commands.
- **Weights:** `400` (regular), `500` (medium), `600` (semibold). Zero italic styling, zero weights above `600`, zero uppercase wide-tracked labels.

## Spacing, Radius & Motion
- **Spacing Scale:** Multiples of `4px` (`4, 8, 12, 16, 24, 32, 48, 64, 80, 96px`).
- **Corner Radius (Nested Rule):** Inner radius = outer radius minus padding, minimum `4px`. Outer cards `rounded-2xl` (`16px`) / `rounded-3xl` (`24px`), nested controls `rounded-lg` (`8px`) / `rounded-xl` (`12px`).
- **Icons:** `@phosphor-icons/react` (`16px`–`20px`), chosen for consistent crisp line geometry pairing with Lumyn's 5-bar geometric emblem.
- **Motion:** Single curve `cubic-bezier(0.22, 1, 0.36, 1)` animating only `transform` and `opacity`, honoring `prefers-reduced-motion: reduce`.

## Copy & Anti-Slop Rules
- No em dashes or double hyphens inside UI copy in either English or Indonesian.
- No fabricated metrics, testimonials, or hype buzzwords. Every number, permission statement, and checksum corresponds directly to verified files in the repository or GitHub Releases API.
