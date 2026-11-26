export interface ApkAsset {
  abi: string;
  label: string;
  description: {
    en: string;
    id: string;
  };
  fileName: string;
  sizeBytes: number;
  sizeFormatted: string;
  sha256: string;
  downloadUrl: string;
  recommended?: boolean;
}

export interface ReleaseInfo {
  tagName: string;
  name: string;
  publishedAt: string;
  htmlUrl: string;
  assets: ApkAsset[];
}

export interface ReleaseResult {
  release: ReleaseInfo;
  source: "github" | "fallback";
}

export const GITHUB_REPO_URL = "https://github.com/Dawnistrator/Lumyn";
export const GITHUB_RELEASES_URL = "https://github.com/Dawnistrator/Lumyn/releases/latest";
export const GITHUB_ISSUES_URL = "https://github.com/Dawnistrator/Lumyn/issues";

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 MB";
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export const FALLBACK_RELEASE: ReleaseInfo = {
  tagName: "v1.0.0",
  name: "Lumyn v1.0.0",
  publishedAt: "2026-11-26T09:35:38Z",
  htmlUrl: "https://github.com/Dawnistrator/Lumyn/releases/tag/v1.0.0",
  assets: [
    {
      abi: "arm64-v8a",
      label: "arm64-v8a",
      description: {
        en: "Modern 64-bit ARM phones (Android 8.0+, smallest download)",
        id: "Ponsel ARM 64-bit modern (Android 8.0+, ukuran paling ringkas)",
      },
      fileName: "Lumyn-v1.0.0-arm64-v8a.apk",
      sizeBytes: 17965199,
      sizeFormatted: formatBytes(17965199),
      sha256: "ab0702ada14dd594afa9ed6c96f6f43da476b9f53a851be381433bc3ab45368c",
      downloadUrl: "https://github.com/Dawnistrator/Lumyn/releases/download/v1.0.0/Lumyn-v1.0.0-arm64-v8a.apk",
      recommended: true,
    },
    {
      abi: "universal",
      label: "universal",
      description: {
        en: "Works on any Android CPU architecture if you are unsure",
        id: "Kompatibel dengan semua arsitektur CPU Android jika belum yakin",
      },
      fileName: "Lumyn-v1.0.0-universal.apk",
      sizeBytes: 62128702,
      sizeFormatted: formatBytes(62128702),
      sha256: "7cf30300f115ee04868ee3d0cf39d6ed730b6ed53b362653e0885ae29939a207",
      downloadUrl: "https://github.com/Dawnistrator/Lumyn/releases/download/v1.0.0/Lumyn-v1.0.0-universal.apk",
    },
    {
      abi: "armeabi-v7a",
      label: "armeabi-v7a",
      description: {
        en: "Older 32-bit ARM Android phones and dedicated DAPs",
        id: "Perangkat ARM 32-bit lama dan pemutar DAP lawas",
      },
      fileName: "Lumyn-v1.0.0-armeabi-v7a.apk",
      sizeBytes: 16718537,
      sizeFormatted: formatBytes(16718537),
      sha256: "020837d35adf634065dc47bdbeadae1251bfe074d75969bb5d53a2108f7d0d68",
      downloadUrl: "https://github.com/Dawnistrator/Lumyn/releases/download/v1.0.0/Lumyn-v1.0.0-armeabi-v7a.apk",
    },
    {
      abi: "x86_64",
      label: "x86_64",
      description: {
        en: "64-bit x86 Android emulators and ChromeOS devices",
        id: "Emulator Android 64-bit dan perangkat ChromeOS x86_64",
      },
      fileName: "Lumyn-v1.0.0-x86_64.apk",
      sizeBytes: 22116940,
      sizeFormatted: formatBytes(22116940),
      sha256: "0f8fd743d3a8a6f79da7d3006c995617f64aef5de7540dc8348dde5e51da245e",
      downloadUrl: "https://github.com/Dawnistrator/Lumyn/releases/download/v1.0.0/Lumyn-v1.0.0-x86_64.apk",
    },
    {
      abi: "x86",
      label: "x86",
      description: {
        en: "32-bit x86 emulators and legacy Intel Atom tablets",
        id: "Emulator 32-bit dan tablet Intel Atom lawas",
      },
      fileName: "Lumyn-v1.0.0-x86.apk",
      sizeBytes: 19453633,
      sizeFormatted: formatBytes(19453633),
      sha256: "b08ba3a173b611dbc1e1c7fc13eadc3ebc3e45ecaa93bc1832023aec0977c949",
      downloadUrl: "https://github.com/Dawnistrator/Lumyn/releases/download/v1.0.0/Lumyn-v1.0.0-x86.apk",
    },
  ],
};

const ABI_DESCRIPTIONS: Record<string, { en: string; id: string }> = {
  "arm64-v8a": {
    en: "Modern 64-bit ARM phones (Android 8.0+, smallest download)",
    id: "Ponsel ARM 64-bit modern (Android 8.0+, ukuran paling ringkas)",
  },
  universal: {
    en: "Works on any Android CPU architecture if you are unsure",
    id: "Kompatibel dengan semua arsitektur CPU Android jika belum yakin",
  },
  "armeabi-v7a": {
    en: "Older 32-bit ARM Android phones and dedicated DAPs",
    id: "Perangkat ARM 32-bit lama dan pemutar DAP lawas",
  },
  x86_64: {
    en: "64-bit x86 Android emulators and ChromeOS devices",
    id: "Emulator Android 64-bit dan perangkat ChromeOS x86_64",
  },
  x86: {
    en: "32-bit x86 emulators and legacy Intel Atom tablets",
    id: "Emulator 32-bit dan tablet Intel Atom lawas",
  },
};

const ABI_ORDER = ["arm64-v8a", "universal", "armeabi-v7a", "x86_64", "x86"];

function extractAbi(fileName: string): string {
  for (const abi of ABI_ORDER) {
    if (fileName.includes(abi)) return abi;
  }
  return fileName.replace(/\.apk$/i, "");
}

function parseSha256Sums(text: string): Record<string, string> {
  const map: Record<string, string> = {};
  for (const line of text.split(/\r?\n/)) {
    const match = line.trim().match(/^([a-fA-F0-9]{64})\s+\*?(.+)$/);
    if (match) {
      map[match[2].trim()] = match[1].toLowerCase();
    }
  }
  return map;
}

interface GitHubAssetDto {
  name: string;
  size: number;
  browser_download_url: string;
}

interface GitHubReleaseDto {
  tag_name: string;
  name?: string;
  published_at?: string;
  html_url: string;
  assets?: GitHubAssetDto[];
}

export async function fetchLatestRelease(forceFresh = false): Promise<ReleaseResult> {
  try {
    const response = await fetch("https://api.github.com/repos/Dawnistrator/Lumyn/releases/latest", {
      headers: {
        Accept: "application/vnd.github+json",
      },
      ...(forceFresh ? { cache: "no-store" as const } : { next: { revalidate: 3600 } }),
      signal: AbortSignal.timeout(4500),
    });

    if (!response.ok) {
      return { release: FALLBACK_RELEASE, source: "fallback" };
    }

    const data = (await response.json()) as GitHubReleaseDto;
    const rawAssets = data.assets ?? [];
    const apkAssetsRaw = rawAssets.filter((a) => a.name.toLowerCase().endsWith(".apk"));
    if (apkAssetsRaw.length === 0) {
      return { release: FALLBACK_RELEASE, source: "fallback" };
    }

    let shaMap: Record<string, string> = {};
    const shaAsset = rawAssets.find((a) => a.name === "SHA256SUMS.txt");
    if (shaAsset) {
      try {
        const shaRes = await fetch(shaAsset.browser_download_url, {
          ...(forceFresh ? { cache: "no-store" as const } : { next: { revalidate: 3600 } }),
          signal: AbortSignal.timeout(3500),
        });
        if (shaRes.ok) {
          shaMap = parseSha256Sums(await shaRes.text());
        }
      } catch {
        // Fallback hashes remain empty for unparsed assets
      }
    }

    const fallbackHashes = Object.fromEntries(FALLBACK_RELEASE.assets.map((a) => [a.fileName, a.sha256]));

    const assets: ApkAsset[] = apkAssetsRaw
      .map((asset) => {
        const abi = extractAbi(asset.name);
        return {
          abi,
          label: abi,
          description: ABI_DESCRIPTIONS[abi] ?? {
            en: "Signed Android APK package",
            id: "Paket APK Android bertanda tangan resmi",
          },
          fileName: asset.name,
          sizeBytes: asset.size,
          sizeFormatted: formatBytes(asset.size),
          sha256: shaMap[asset.name] ?? fallbackHashes[asset.name] ?? "",
          downloadUrl: asset.browser_download_url,
          recommended: abi === "arm64-v8a",
        };
      })
      .sort((a, b) => {
        const ia = ABI_ORDER.indexOf(a.abi);
        const ib = ABI_ORDER.indexOf(b.abi);
        return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
      });

    return {
      release: {
        tagName: data.tag_name || FALLBACK_RELEASE.tagName,
        name: data.name || `Lumyn ${data.tag_name || FALLBACK_RELEASE.tagName}`,
        publishedAt: data.published_at || FALLBACK_RELEASE.publishedAt,
        htmlUrl: data.html_url || FALLBACK_RELEASE.htmlUrl,
        assets,
      },
      source: "github",
    };
  } catch {
    return { release: FALLBACK_RELEASE, source: "fallback" };
  }
}
