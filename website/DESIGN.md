# Lunaria Web Design System

## Direction
- **Product:** Offline Hi-Res Android and Windows music player for local FLAC, WAV, ALAC, and MP3 libraries with a 32-bit float DSP chain, bit-perfect USB DAC output, and zero network permissions.
- **Character:** Calm, exact, tactile (audio studio instrumentation rather than generic SaaS).
- **World:** Dark obsidian slate tinted toward deep studio indigo (`#0D101B`) anchored by Lunaria's native seed color (`#32428A`), paired with a crisp daytime light slate mode (`#F5F7FC`).
- **Moments:**
  1. Interactive Audio Signal Path switcher (`DSP 32-bit Float` vs `Purist Bit-Perfect`) detailing each real stage from local file to DAC.
  2. Live APK & Windows desktop asset selector (`arm64-v8a`, `universal`, `armeabi-v7a`, `x86_64`, `x86`, portable ZIP / NSIS installer) with GitHub Release asset sizes, SHA-256 copy, and `aapt2` zero-permission proof.
- **Signature:** Concept B Lunaria Monogram (architectural rounded `L` cradling an upper-right crescent moon and pulse dot) from `ic_launcher_foreground.xml` used as the brand mark, favicon, and OG emblem.
- **Dials:** `ENERGY 2 (Balanced)` / `RHYTHM 3 (Varied)` / `MOTION 2 (Purposeful transitions & word-by-word tagline reveal)`

## Color Tokens (WCAG AA Pre-Verified)

### Dark Theme (`[data-theme="dark"]`, Default)
| Token | Hex / Value | Usage |
| :--- | :--- | :--- |
| `--bg` | `#0D101B` | Page background (`lunaria_window_background` night) |
| `--surface` | `#141827` | Cards, navigation sheet, code panels |
| `--surface-raised` | `#1C2236` | Active tabs, hover surfaces, raised controls |
| `--text-primary` | `#F4F6FC` | Primary headlines and body (17.56:1 on `--bg`) |
| `--text-secondary` | `#B8C0D9` | Supporting prose (10.46:1 on `--bg`) |
| `--text-muted` | `#96A0BF` | Captions and metadata (7.30:1 on `--bg`) |
| `--accent` | `#9AB0FF` | Selected state highlights and links on dark surfaces (9.06:1 on `--bg`) |
| `--accent-fill` | `#32428A` | Brand seed solid fill (`LunariaBrandSeedArgb`) |
| `--on-accent` | `#F4F6FC` | Text over `#32428A` fill (8.52:1) |
| `--ui-border` | `#6D789C` | Interactive control borders (4.05:1 on `--surface`) |
| `--hairline` | `rgba(244, 246, 252, 0.12)` | Subtle full-perimeter card dividers |
| `--focus-ring` | `#9AB0FF` | Keyboard focus ring |

### Light Theme (`[data-theme="light"]`)
| Token | Hex / Value | Usage |
| :--- | :--- | :--- |
| `--bg` | `#F5F7FC` | Page background (`lunaria_window_background` day) |
| `--surface` | `#FFFFFF` | Cards and panels |
| `--surface-raised` | `#E8ECF7` | Raised rows, active tabs, hover states |
| `--text-primary` | `#0E1224` | Primary headlines and body (17.33:1 on `--bg`) |
| `--text-secondary` | `#373F5C` | Supporting prose (9.66:1 on `--bg`) |
| `--text-muted` | `#4D5675` | Captions and metadata (6.75:1 on `--bg`) |
| `--accent` | `#2A3B85` | Accessible dark indigo links/accents on light surfaces (9.50:1 on `--bg`) |
| `--accent-fill` | `#32428A` | Primary brand fill |
| `--on-accent` | `#F4F6FC` | Text over `#32428A` fill (8.52:1) |
| `--ui-border` | `#6F7896` | Interactive control borders (4.37:1 on `--surface`) |
| `--hairline` | `rgba(14, 18, 36, 0.12)` | Full-perimeter card borders |
| `--focus-ring` | `#2A3B85` | Keyboard focus ring |

## Typography
- **Primary (`--font-sans`):** `Geist` (clean geometric readability in English and Indonesian; chosen over Android-bundled monospace body to ensure long-form technical legibility).
- **Monospace (`--font-mono`):** `Geist Mono` with `tabular-nums`, used exclusively for sample rates, bit depths, APK byte sizes, `SHA-256` digests, and CLI verification commands.
- **Weights:** `400` (regular), `500` (medium), `600` (semibold). Zero italic styling, zero weights above `600`, zero uppercase wide-tracked labels.

## Spacing, Radius & Motion
- **Spacing Scale:** Multiples of `4px` (`4, 8, 12, 16, 24, 32, 48, 64, 80, 96px`).
- **Corner Radius (Nested Rule):** Inner radius = outer radius minus padding, minimum `4px`. Outer cards `rounded-2xl` (`16px`) / `rounded-3xl` (`24px`), nested controls `rounded-lg` (`8px`) / `rounded-xl` (`12px`).
- **Icons:** `@phosphor-icons/react` (`16px`–`20px`), chosen for consistent crisp line geometry pairing with Lunaria's geometric monogram mark.
- **Motion:** Single curve `cubic-bezier(0.22, 1, 0.36, 1)` animating only `transform` and `opacity`, honoring `prefers-reduced-motion: reduce`.

## Copy & Anti-Slop Rules
- No em dashes or double hyphens inside UI copy in either English or Indonesian.
- No fabricated metrics, testimonials, or hype buzzwords. Every number, permission statement, and checksum corresponds directly to verified files in the repository or GitHub Releases API.
